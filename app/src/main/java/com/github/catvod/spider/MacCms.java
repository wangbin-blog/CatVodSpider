package com.github.catvod.spider;

import android.content.Context;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.*;

public class MacCms extends Spider {
    private String baseApi;
    private final Set<String> yearSet = new HashSet<>();
    private final Set<String> areaSet = new HashSet<>();
    private boolean filterLoaded = false;

    @Override
    public void init(Context context, String extend) throws Exception{
        super.init(context, extend);
        baseApi = extend;
        if (baseApi == null || baseApi.isEmpty()) {
            baseApi = "https://caiji.dyttzyapi.com/api.php/provide/vod";
        }
    }

    // 预抓取多页，收集所有年份、地区，构建filter下拉选项
    private void loadFilterOptions() {
        if (filterLoaded) return;
        int maxPage = 3;
        for (int pg = 1; pg <= maxPage; pg++) {
            try {
                String resp = OkHttp.string(baseApi + "?ac=list&pg=" + pg);
                JSONObject json = new JSONObject(resp);
                JSONArray list = json.optJSONArray("list");
                if (list == null) continue;
                for (int i = 0; i < list.length(); i++) {
                    JSONObject item = list.getJSONObject(i);
                    String y = item.optString("vod_year", "").trim();
                    String a = item.optString("vod_area", "").trim();
                    if (!y.isEmpty()) yearSet.add(y);
                    if (!a.isEmpty()) areaSet.add(a);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        filterLoaded = true;
    }

    @Override
    public String homeContent(boolean filter) {
        loadFilterOptions();
        JSONObject result = new JSONObject();
        JSONArray classes = new JSONArray();
        try {
            classes.put(new JSONObject().put("type_id", "1").put("type_name", "电影"));
            classes.put(new JSONObject().put("type_id", "2").put("type_name", "剧集"));
            classes.put(new JSONObject().put("type_id", "3").put("type_name", "综艺"));
            classes.put(new JSONObject().put("type_id", "4").put("type_name", "动漫"));
            result.put("class", classes);

            if (filter) {
                JSONArray filterArr = new JSONArray();
                // 年份筛选
                JSONArray yearValues = new JSONArray();
                yearValues.put(new JSONObject().put("n", "全部").put("v", ""));
                List<String> yearList = new ArrayList<>(yearSet);
                Collections.sort(yearList, Collections.reverseOrder());
                for (String y : yearList) {
                    yearValues.put(new JSONObject().put("n", y).put("v", y));
                }
                filterArr.put(new JSONObject().put("key", "year").put("name", "年份").put("value", yearValues));

                // 地区筛选
                JSONArray areaValues = new JSONArray();
                areaValues.put(new JSONObject().put("n", "全部").put("v", ""));
                List<String> areaList = new ArrayList<>(areaSet);
                Collections.sort(areaList);
                for (String a : areaList) {
                    areaValues.put(new JSONObject().put("n", a).put("v", a));
                }
                filterArr.put(new JSONObject().put("key", "area").put("name", "地区").put("value", areaValues));

                // 完结状态
                JSONArray isendValues = new JSONArray();
                isendValues.put(new JSONObject().put("n", "全部").put("v", ""));
                isendValues.put(new JSONObject().put("n", "完结").put("v", "1"));
                isendValues.put(new JSONObject().put("n", "连载").put("v", "0"));
                filterArr.put(new JSONObject().put("key", "isend").put("name", "状态").put("value", isendValues));
                result.put("filter", filterArr);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return result.toString();
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        JSONObject result = new JSONObject();
        JSONArray list = new JSONArray();
        try {
            StringBuilder urlSb = new StringBuilder(baseApi + "?ac=list");
            urlSb.append("&t=").append(tid);
            urlSb.append("&pg=").append(pg);
            if (extend != null) {
                String year = extend.get("year");
                String area = extend.get("area");
                String isend = extend.get("isend");
                if (year != null && !year.isEmpty()) urlSb.append("&year=").append(year);
                if (area != null && !area.isEmpty()) urlSb.append("&area=").append(area);
                if (isend != null && !isend.isEmpty()) urlSb.append("&isend=").append(isend);
            }
            String resp = OkHttp.string(urlSb.toString());
            JSONObject raw = new JSONObject(resp);
            JSONArray rawList = raw.optJSONArray("list");
            if (rawList != null) {
                for (int i = 0; i < rawList.length(); i++) {
                    JSONObject item = rawList.getJSONObject(i);
                    JSONObject vod = new JSONObject();
                    vod.put("vod_id", item.optString("vod_id"));
                    vod.put("vod_name", item.optString("vod_name"));
                    vod.put("vod_pic", item.optString("vod_pic"));
                    vod.put("vod_remarks", item.optInt("vod_isend") == 1 ? "完结" : "连载");
                    vod.put("vod_year", item.optString("vod_year"));
                    vod.put("vod_area", item.optString("vod_area"));
                    vod.put("vod_content", item.optString("vod_content"));
                    vod.put("vod_play_url", item.optString("vod_play_url"));
                    list.put(vod);
                }
            }
            result.put("page", raw.optInt("page"));
            result.put("pagecount", raw.optInt("totalpage"));
            result.put("list", list);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return result.toString();
    }

    @Override
    public String detailContent(List<String> ids) {
        JSONObject result = new JSONObject();
        JSONArray list = new JSONArray();
        try {
            String id = ids.get(0);
            String resp = OkHttp.string(baseApi + "?ac=detail&ids=" + id);
            JSONObject raw = new JSONObject(resp);
            JSONArray rawList = raw.optJSONArray("list");
            if (rawList != null && rawList.length() > 0) {
                JSONObject item = rawList.getJSONObject(0);
                JSONObject vod = new JSONObject();
                vod.put("vod_id", item.optString("vod_id"));
                vod.put("vod_name", item.optString("vod_name"));
                vod.put("vod_pic", item.optString("vod_pic"));
                vod.put("vod_remarks", item.optInt("vod_isend") == 1 ? "完结" : "连载");
                vod.put("vod_year", item.optString("vod_year"));
                vod.put("vod_area", item.optString("vod_area"));
                vod.put("vod_content", item.optString("vod_content"));
                vod.put("vod_play_url", item.optString("vod_play_url"));
                list.put(vod);
            }
            result.put("list", list);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return result.toString();
    }

    @Override
    public String searchContent(String key, boolean quick) {
        JSONObject result = new JSONObject();
        JSONArray list = new JSONArray();
        try {
            String resp = OkHttp.string(baseApi + "?ac=list&ac=search&wd=" + key);
            JSONObject raw = new JSONObject(resp);
            JSONArray rawList = raw.optJSONArray("list");
            if (rawList != null) {
                for (int i = 0; i < rawList.length(); i++) {
                    JSONObject item = rawList.getJSONObject(i);
                    JSONObject vod = new JSONObject();
                    vod.put("vod_id", item.optString("vod_id"));
                    vod.put("vod_name", item.optString("vod_name"));
                    vod.put("vod_pic", item.optString("vod_pic"));
                    vod.put("vod_remarks", item.optInt("vod_isend") == 1 ? "完结" : "连载");
                    vod.put("vod_year", item.optString("vod_year"));
                    vod.put("vod_area", item.optString("vod_area"));
                    vod.put("vod_content", item.optString("vod_content"));
                    vod.put("vod_play_url", item.optString("vod_play_url"));
                    list.put(vod);
                }
            }
            result.put("list", list);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return result.toString();
    }
}
