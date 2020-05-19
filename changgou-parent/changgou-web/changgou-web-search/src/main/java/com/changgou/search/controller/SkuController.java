package com.changgou.search.controller;

import com.changgou.search.feign.SkuFeign;
import com.changgou.search.pojo.SkuInfo;
import entity.Page;
import io.swagger.models.auth.In;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

@Controller
@RequestMapping(value = "/search")
public class SkuController {

    @Autowired
    private SkuFeign skuFeign;

    /**
     * 搜索
     * @param searchMap
     * @return
     */
    @GetMapping(value = "/list")
    public String search(@RequestParam(required = false) Map searchMap, Model model){
        //调用changgou-service-search微服务
        Map resultMap = skuFeign.search(searchMap);

        model.addAttribute("result",resultMap);

        //分页数据
        Page<SkuInfo> page = new Page<>(
                Long.parseLong(resultMap.get("total").toString()),
                Integer.parseInt(resultMap.get("pageNumber").toString())+1,
                Integer.parseInt(resultMap.get("pageSize").toString())
        );
        model.addAttribute("pageInfo",page);

        //搜索条件
        model.addAttribute("searchMap",searchMap);

        //上次请求的url
        String[] urls = url(searchMap);
        model.addAttribute("url", urls[0]);
        model.addAttribute("sorturl", urls[1]);
        return "search";
    }

    /**
     * 用户请求的url
     * @param searchMap
     * @return
     */
    public String[] url(Map<String,String> searchMap){
        String url = "/search/list";
        String sorturl = "/search/list";
        if (searchMap != null && searchMap.size()>0){
            url+="?";
            sorturl+="?";
            for (Map.Entry<String,String> entry : searchMap.entrySet()){
                String key = entry.getKey();
                String value = entry.getValue();

                //跳过分页参数
                if(key.equalsIgnoreCase("pageNum")){
                    continue;
                }

                url += key + "=" + value + "&";

                if (key.equalsIgnoreCase("sortField") || key.equalsIgnoreCase("sortRule")){
                    continue;
                }

                sorturl += key + "=" + value + "&";
            }
            url = url.substring(0,url.length()-1);
            sorturl = sorturl.substring(0,sorturl.length()-1);
        }
        return new String[]{url, sorturl};
    }
}
