package com.changgou.seach.service;

import java.util.Map;

public interface SkuService {

    /**
     * 导入数据到索引库
     */
    void importData();

    /**
     * 搜索
     * @param searchMap
     * @return
     */
    Map search(Map<String, String> searchMap);
}
