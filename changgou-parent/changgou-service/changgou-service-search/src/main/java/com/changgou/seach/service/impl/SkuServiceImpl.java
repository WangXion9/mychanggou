package com.changgou.seach.service.impl;

import com.alibaba.fastjson.JSON;
import com.changgou.goods.feign.SkuFeign;
import com.changgou.goods.pojo.Sku;
import com.changgou.seach.dao.SkuEsMapper;
import com.changgou.seach.service.SkuService;
import com.changgou.search.pojo.SkuInfo;
import entity.Result;
import io.swagger.models.auth.In;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.common.text.Text;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.aggregations.Aggregation;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.Aggregations;
import org.elasticsearch.search.aggregations.bucket.terms.StringTerms;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightBuilder;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightField;
import org.elasticsearch.search.sort.FieldSortBuilder;
import org.elasticsearch.search.sort.SortOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.core.ElasticsearchTemplate;
import org.springframework.data.elasticsearch.core.SearchResultMapper;
import org.springframework.data.elasticsearch.core.aggregation.AggregatedPage;
import org.springframework.data.elasticsearch.core.aggregation.impl.AggregatedPageImpl;
import org.springframework.data.elasticsearch.core.query.NativeSearchQuery;
import org.springframework.data.elasticsearch.core.query.NativeSearchQueryBuilder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.*;

@Service
public class SkuServiceImpl implements SkuService {

    @Autowired
    private SkuFeign skuFeign;

    @Autowired
    private SkuEsMapper skuEsMapper;

    @Autowired
    private ElasticsearchTemplate esTemplate;

    /**
     * 导入数据到索引库
     */
    @Override
    public void importData() {
        //调用feign，查询所有
        Result<List<Sku>> skuList = skuFeign.findAll();
        //将List<Sku>转成List<SkuInfo>
        String json = JSON.toJSONString(skuList.getData());
        List<SkuInfo> skuInfos = JSON.parseArray(json, SkuInfo.class);

        for(SkuInfo skuInfo:skuInfos){
            Map<String, Object> specMap= JSON.parseObject(skuInfo.getSpec()) ;
            skuInfo.setSpecMap(specMap);
        }
        //调用dao实现批量导入
        skuEsMapper.saveAll(skuInfos);
    }

    /**
     * 搜索
     * @param searchMap
     * @return
     */
    @Override
    public Map search(Map<String, String> searchMap) {

        //搜索条件封装
        NativeSearchQueryBuilder nativeSearchQueryBuilder = buildBasicQuery(searchMap);

        //集合搜索
        Map<String, Object> resultMap = searchList(nativeSearchQueryBuilder);

        if (searchMap == null || StringUtils.isEmpty(searchMap.get("category"))){
            //分类分组查询
            List<String> categoryList = searchCategoryList(nativeSearchQueryBuilder);
            resultMap.put("categoryList", categoryList);
        }

        if (searchMap == null || StringUtils.isEmpty(searchMap.get("brand"))){
            //查询品牌集合【搜索条件】
            List<String> brandList = searchBrandList(nativeSearchQueryBuilder);
            resultMap.put("brandList", brandList);
        }

        //规格查询
        Map<String, Set<String>> specList = searchSpecList(nativeSearchQueryBuilder);
        resultMap.put("specList", specList);

        return resultMap;
    }

    /**
     * 搜索条件封装
     * @param searchMap
     * @return
     */
    private NativeSearchQueryBuilder buildBasicQuery(Map<String, String> searchMap) {
        //搜索条件构造对象
        NativeSearchQueryBuilder nativeSearchQueryBuilder = new NativeSearchQueryBuilder();

        //BoolQuery must,must_not,should
        BoolQueryBuilder boolQueryBuilder = QueryBuilders.boolQuery();

        if (searchMap != null && searchMap.size() > 0){
            //根据关键词搜索
            String keywords = searchMap.get("keywords");
            if (!StringUtils.isEmpty(keywords)){
                // nativeSearchQueryBuilder.withQuery(QueryBuilders.queryStringQuery(keywords).field("name"));
                boolQueryBuilder.must(QueryBuilders.queryStringQuery(keywords).field("name"));
            }

            //输入了分类
            String category = searchMap.get("category");
            if (!StringUtils.isEmpty(category)){
                boolQueryBuilder.must(QueryBuilders.termQuery("categoryName",category));
            }

            //输入了品牌
            String brand = searchMap.get("brand");
            if (!StringUtils.isEmpty(brand)){
                boolQueryBuilder.must(QueryBuilders.termQuery("brandName",brand));
            }

            //输入规格
            for (Map.Entry<String,String> entry: searchMap.entrySet()){
                String key = entry.getKey();
                if (key.startsWith("spec_")){
                    //规格条件的值
                    String value = entry.getValue();
                    //spec_颜色
                    key = key.substring(5);
                    boolQueryBuilder.must(QueryBuilders.termQuery("specMap."+key+".keyword",value));
                }
            }

            /*//规格
            for(String key:searchMap.keySet()){
                //如果是规格参数
                if(key.startsWith("spec_")){
                    boolQueryBuilder.must(QueryBuilders.matchQuery("specMap."+key.substring(5)+".keyword", searchMap.get(key)));
                }
            }*/

            //价格区间搜索
            String price = searchMap.get("price");
            if (!StringUtils.isEmpty(price)){
                //分割
                price = price.replace("元", "").replace("以上", "");
                String[] prices = price.split("-");

                if (prices!=null&&prices.length>0){
                    boolQueryBuilder.must(QueryBuilders.rangeQuery("price").gt(Integer.parseInt(prices[0])));
                    if (prices.length==2){
                        boolQueryBuilder.must(QueryBuilders.rangeQuery("price").lte(Integer.parseInt(prices[1])));
                    }
                }
            }

            //排序实现
            String sortField = searchMap.get("sortField");      //指定排序的域
            String sortRule = searchMap.get("sortRule");      //指定排序的规则
            if (!StringUtils.isEmpty(sortField) && !StringUtils.isEmpty(sortRule)){
                nativeSearchQueryBuilder.withSort(
                        new FieldSortBuilder(sortField)         //指定排序域
                                .order(SortOrder.valueOf(sortRule)));//指定排序规则
            }

        }

        //分页
        Integer pageNum = coverterPage(searchMap);//默认第一页
        Integer pageSize = 15;//默认查询3条
        nativeSearchQueryBuilder.withPageable(PageRequest.of(pageNum-1, pageSize));


        nativeSearchQueryBuilder.withQuery(boolQueryBuilder);
        return nativeSearchQueryBuilder;
    }

    /**
     * 接收前端传的分页参数
     * @param searchMap
     * @return
     */
    public Integer coverterPage(Map<String,String> searchMap){
        if (searchMap != null){
            String pageNum = searchMap.get("pageNum");
            try {
                return Integer.parseInt(pageNum);
            }catch (NumberFormatException e){
            }
        }
        return 1;
    }

    /**
     * 集合搜索
     * @param nativeSearchQueryBuilder
     * @return
     */
    private Map<String, Object> searchList(NativeSearchQueryBuilder nativeSearchQueryBuilder) {

        //设置高亮配置
        HighlightBuilder.Field field = new HighlightBuilder.Field("name");
        //前缀
        field.preTags("<em style=\"color:red;\">");
        //后缀
        field.postTags("</em>");
        //碎片长度
        field.fragmentSize(100);

        nativeSearchQueryBuilder.withHighlightFields(field);

        //执行搜索，相应结果
        // AggregatedPage<SkuInfo> pages = esTemplate.queryForPage(nativeSearchQueryBuilder.build(), SkuInfo.class);
        AggregatedPage<SkuInfo> pages = esTemplate
                .queryForPage(
                        nativeSearchQueryBuilder.build(),           //搜索条件封装
                        SkuInfo.class,                              //字节码
                        new SearchResultMapper() {                  //执行搜索后，封装数据
                            @Override
                            public <T> AggregatedPage<T> mapResults(SearchResponse response, Class<T> aClass, Pageable pageable) {
                                List<T> list = new ArrayList<>();
                                //执行查询，所有数据【高亮|非高亮】
                                for(SearchHit hit:response.getHits()){
                                    //分析结果，获取非高亮数据
                                    SkuInfo skuInfo = JSON.parseObject(hit.getSourceAsString(),SkuInfo.class);
                                    //分析结果，获取高亮数据
                                    HighlightField highlightField = hit.getHighlightFields().get("name");
                                    if (highlightField != null && highlightField.getFragments() != null){
                                        //高亮数据读出来
                                        Text[] fragments = highlightField.getFragments();
                                        StringBuffer buffer = new StringBuffer();
                                        for (Text fragment : fragments){
                                            buffer.append(fragment.toString());
                                        }
                                        //非高亮数据替换
                                        skuInfo.setName(buffer.toString());
                                    }

                                    list.add((T)skuInfo);

                                }

                                return new AggregatedPageImpl<T>(list, pageable, response.getHits().getTotalHits());
                            }
                        }
                );


        //分页参数->总记录数
        long totalElements = pages.getTotalElements();

        //总页数
        int totalPages = pages.getTotalPages();

        //获取数据结果集
        List<SkuInfo> contents = pages.getContent();

        //封装一个Map存储所有数据，并返回
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("total", totalElements);
        resultMap.put("totalPages", totalPages);
        resultMap.put("rows", contents);

        //分页数据
        NativeSearchQuery query = nativeSearchQueryBuilder.build();
        Pageable pageable = query.getPageable();
        int pageSize = pageable.getPageSize();
        int pageNumber = pageable.getPageNumber();
        resultMap.put("pageSize", pageSize);
        resultMap.put("pageNumber", pageNumber);
        return resultMap;
    }

    /**
     * 分类分组查询
     * @param nativeSearchQueryBuilder
     * @return
     */
    public List<String> searchCategoryList(NativeSearchQueryBuilder nativeSearchQueryBuilder){
        //分组查询分类集合
        //添加一个聚合操作
        nativeSearchQueryBuilder.addAggregation(AggregationBuilders.terms("skuCategory").field("categoryName"));
        AggregatedPage<SkuInfo> aggregatedPage = esTemplate.queryForPage(nativeSearchQueryBuilder.build(), SkuInfo.class);

        /**
         * 获取分组数据
         * aggregatedPage.getAggregations()获取的集合
         */
        StringTerms stringTerms = aggregatedPage.getAggregations().get("skuCategory");
        List<String> categoryList = new ArrayList<>();
        for (StringTerms.Bucket bucket : stringTerms.getBuckets()) {
            String categoryName = bucket.getKeyAsString();//其中一个分类名字
            categoryList.add(categoryName);
        }
        return categoryList;
    }

    /**
     * 品牌分组查询
     * @param nativeSearchQueryBuilder
     * @return
     */
    public List<String> searchBrandList(NativeSearchQueryBuilder nativeSearchQueryBuilder){
        //品牌查询分类集合
        //添加一个聚合操作
        nativeSearchQueryBuilder.addAggregation(AggregationBuilders.terms("skuBrand").field("brandName"));
        AggregatedPage<SkuInfo> aggregatedPage = esTemplate.queryForPage(nativeSearchQueryBuilder.build(), SkuInfo.class);

        /**
         * 获取品牌数据
         * aggregatedPage.getAggregations()获取的集合
         */
        StringTerms stringTerms = aggregatedPage.getAggregations().get("skuBrand");
        List<String> brandList = new ArrayList<>();
        for (StringTerms.Bucket bucket : stringTerms.getBuckets()) {
            String brandName = bucket.getKeyAsString();//其中一个品牌名字
            brandList.add(brandName);
        }
        return brandList;
    }

    /**
     * 规格分组查询
     * @param nativeSearchQueryBuilder
     * @return
     */
    public Map<String, Set<String>> searchSpecList(NativeSearchQueryBuilder nativeSearchQueryBuilder){
        //分组查询规格集合
        //添加一个聚合操作
        nativeSearchQueryBuilder.addAggregation(AggregationBuilders.terms("skuSpec").field("spec.keyword").size(10000));
        AggregatedPage<SkuInfo> aggregatedPage = esTemplate.queryForPage(nativeSearchQueryBuilder.build(), SkuInfo.class);

        /**
         * 获取分组数据
         * aggregatedPage.getAggregations()获取的集合
         */
        StringTerms stringTerms = aggregatedPage.getAggregations().get("skuSpec");
        List<String> specList = new ArrayList<>();
        for (StringTerms.Bucket bucket : stringTerms.getBuckets()) {
            String specName = bucket.getKeyAsString();//其中一个规格名字
            specList.add(specName);
        }

        //合并后的map
        Map<String, Set<String>> allMap = new HashMap<>();

        //1.循环specList
        for (String spec:specList) {
            //2.将每个json字符串转成map
            Map<String,String> specMap = JSON.parseObject(spec, Map.class);
            //3.将每个Map对象合成一个Map<String,Set<String>>
            //4.合并流程

            for (Map.Entry<String,String> entry:specMap.entrySet()) {
                //4.1循环所有map
                String key = entry.getKey();
                String value = entry.getValue();

                //4.2取出当前map，并且获取响应的key
                Set<String> specSet = allMap.get(key);
                if (specSet == null){
                    specSet = new HashSet<>();
                }
                specSet.add(value);
                allMap.put(key, specSet);
            }


        }
        return allMap;

    }


}
