package com.github.catvod.spider;

import android.content.Context;

import com.github.catvod.crawler.Spider;
import com.github.catvod.net.Net;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;

/**
 * 电影天堂资源采集接口（苹果CMS provide/vod 标准格式）。
 * 接口：https://caiji.dyttzyapi.com/api.php/provide/vod
 * 支持分类、筛选、分页、搜索、详情与播放；播放线路含 dytt（分享页，需嗅探/解析）
 * 与 dyttm3u8（直链 m3u8，需带 Referer）。
 *
 * ext 配置为 JSON 对象：
 * {"urlapi":"采集接口地址","config":"分类与筛选 JSON 文件地址"}
 * config 文件包含 types（一级分类，每项带 sub 二级分类数组）与 filters（共用的
 * class/area/year 筛选行），首次 init 时网络读取并缓存到静态变量。
 * 接口的 t 参数只认二级分类 ID：class 使用一级分类，二级分类作为"分类"筛选行；
 * 未选二级时并行请求该一级下所有二级并合并当页。
 */
public class Dyttzy extends Spider {

    private static final String API = "https://caiji.dyttzyapi.com/api.php/provide/vod/";

    // 分类与筛选配置，首次 init 时从 config 地址读取后缓存
    private static JSONArray types;
    private static JSONArray filterRows;

    private String api;

    @Override
    public void init(Context context, String extend) throws Exception {
        JSONObject options = new JSONObject(extend == null || extend.isEmpty() ? "{}" : extend);
        // urlapi 留空使用默认电影天堂采集接口
        String urlapi = options.optString("urlapi", API);
        api = urlapi.endsWith("/") ? urlapi : urlapi + "/";
        if (types == null) {
            // config 地址必填，读取失败直接抛出异常，不伪装成空配置
            JSONObject config = new JSONObject(net.json(options.getString("config"), "{}"));
            types = config.getJSONArray("types");
            filterRows = config.getJSONArray("filters");
        }
    }

    /** 请求 JSON，options 通过 params 自动编码查询参数 */
    private JSONObject request(JSONObject params) throws Exception {
        String options = new JSONObject().put("params", params).toString();
        return new JSONObject(net.json(api, options));
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        JSONArray classes = new JSONArray();
        for (int i = 0; i < types.length(); i++) {
            JSONObject type = types.getJSONObject(i);
            classes.put(new JSONObject().put("type_id", type.getString("id")).put("type_name", type.getString("name")));
        }
        JSONObject result = new JSONObject().put("class", classes);
        if (filter) result.put("filters", filters());
        return result.toString();
    }

    /** 一级分类的二级子分类 ID 列表 */
    private JSONArray subIds(String tid) throws Exception {
        JSONArray ids = new JSONArray();
        for (int i = 0; i < types.length(); i++) {
            JSONObject type = types.getJSONObject(i);
            if (!type.getString("id").equals(tid)) continue;
            JSONArray sub = type.optJSONArray("sub");
            for (int j = 0; sub != null && j < sub.length(); j++) ids.put(sub.getJSONObject(j).getString("id"));
            return ids;
        }
        return null;
    }

    /** 首页推荐：取接口最新更新的影片 */
    @Override
    public String homeVideoContent() throws Exception {
        JSONObject json = request(new JSONObject().put("ac", "videolist").put("pg", 1));
        return new JSONObject().put("list", cards(json.optJSONArray("list"))).toString();
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        String sub = extend == null ? null : extend.get("t");
        boolean all = sub == null || sub.isEmpty() || sub.equals("all");
        JSONArray subs;
        if (!all) subs = new JSONArray().put(sub);
        else {
            subs = subIds(tid);
            // 无二级配置时把 tid 当作二级分类直接请求
            if (subs == null || subs.length() == 0) subs = new JSONArray().put(tid);
        }
        if (subs.length() == 1) {
            JSONObject json = request(listParams(subs.getString(0), pg, extend));
            return page(json, pg).toString();
        }
        // 未选二级分类：并行请求该一级下所有二级，合并当页（接口不支持一次查多个 t）
        JSONArray requests = new JSONArray();
        for (int i = 0; i < subs.length(); i++) {
            requests.put(new JSONObject().put("url", api).put("options", new JSONObject().put("params", listParams(subs.getString(i), pg, extend))));
        }
        JSONArray items = new JSONArray();
        int pagecount = 1;
        long total = 0;
        boolean hasTotal = true;
        for (Net.Result response : net.batch(requests.toString())) {
            if (response.error != null) throw new IllegalStateException(response.error);
            if ((Integer) response.code < 200 || (Integer) response.code >= 300) throw new IllegalStateException("HTTP " + response.code);
            JSONObject json = new JSONObject(String.valueOf(response.body));
            JSONArray list = json.optJSONArray("list");
            for (int i = 0; list != null && i < list.length(); i++) items.put(card(list.getJSONObject(i)));
            pagecount = Math.max(pagecount, json.optInt("pagecount", 1));
            if (json.has("total")) total += json.optLong("total");
            else hasTotal = false;
        }
        JSONObject result = new JSONObject().put("list", items).put("page", parseInt(pg, 1)).put("pagecount", pagecount).put("limit", items.length());
        if (hasTotal) result.put("total", total);
        return result.toString();
    }

    /** 列表请求参数：t + 页码，并透传 class/area/year 筛选值 */
    private JSONObject listParams(String t, String pg, HashMap<String, String> extend) throws Exception {
        JSONObject params = new JSONObject().put("ac", "videolist").put("t", t).put("pg", pg);
        if (extend != null) {
            for (String key : new String[]{"class", "area", "year"}) {
                String value = extend.get(key);
                if (value != null && !value.isEmpty() && !value.equals("all")) params.put(key, value);
            }
        }
        return params;
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) throws Exception {
        JSONObject json = request(new JSONObject().put("ac", "videolist").put("wd", key).put("pg", pg));
        JSONArray list = cards(json.optJSONArray("list"));
        JSONObject result = new JSONObject().put("list", list);
        putPaging(result, json, pg);
        return result.toString();
    }

    /** 详情：接口 ac=detail 返回完整字段，播放值直接使用接口原格式 */
    @Override
    public String detailContent(List<String> ids) throws Exception {
        JSONObject json = request(new JSONObject().put("ac", "detail").put("ids", String.join(",", ids)));
        JSONArray list = json.optJSONArray("list");
        if (list == null || list.length() == 0) return error("没有找到该影片");
        JSONArray items = new JSONArray();
        for (int i = 0; i < list.length(); i++) {
            JSONObject vod = list.getJSONObject(i);
            JSONObject item = new JSONObject();
            item.put("vod_id", vod.optString("vod_id"));
            item.put("vod_name", vod.optString("vod_name"));
            item.put("vod_pic", vod.optString("vod_pic"));
            item.put("vod_remarks", vod.optString("vod_remarks"));
            item.put("type_name", vod.optString("type_name"));
            item.put("vod_year", vod.optString("vod_year"));
            item.put("vod_area", vod.optString("vod_area"));
            item.put("vod_actor", vod.optString("vod_actor"));
            item.put("vod_director", vod.optString("vod_director"));
            item.put("vod_content", stripHtml(vod.optString("vod_blurb").isEmpty() ? vod.optString("vod_content") : vod.optString("vod_blurb")));
            item.put("vod_play_from", vod.optString("vod_play_from"));
            item.put("vod_play_url", vod.optString("vod_play_url"));
            items.put(item);
        }
        return new JSONObject().put("list", items).toString();
    }

    /**
     * 播放：id 以 .m3u8 结尾视为直链，带 Referer 直接返回；
     * 否则视为分享页等非直链，返回 parse=1 交给 App 嗅探/解析。
     */
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        JSONObject result = new JSONObject();
        if (id.endsWith(".m3u8")) {
            result.put("parse", 0).put("jx", 0).put("url", id)
                    .put("header", new JSONObject().put("Referer", "https://vip.dytt-luck.com/"));
        } else {
            result.put("parse", 1).put("jx", 0).put("url", id);
        }
        return result.toString();
    }

    /** 列表 JSON 转为 VOD 卡片 */
    private JSONArray cards(JSONArray source) throws Exception {
        JSONArray items = new JSONArray();
        if (source == null) return items;
        for (int i = 0; i < source.length(); i++) items.put(card(source.getJSONObject(i)));
        return items;
    }

    /** 单条接口数据转为 VOD 卡片 */
    private JSONObject card(JSONObject vod) throws Exception {
        return new JSONObject()
                .put("vod_id", vod.optString("vod_id"))
                .put("vod_name", vod.optString("vod_name"))
                .put("vod_pic", vod.optString("vod_pic"))
                .put("vod_remarks", vod.optString("vod_remarks"))
                .put("type_id", vod.optString("type_id"))
                .put("type_name", vod.optString("type_name"));
    }

    /** 按接口分页字段组装当页结果（网站已分页，不再切片） */
    private JSONObject page(JSONObject json, String pg) throws Exception {
        JSONObject result = new JSONObject().put("list", cards(json.optJSONArray("list")));
        putPaging(result, json, pg);
        return result;
    }

    private void putPaging(JSONObject result, JSONObject json, String pg) {
        int page = json.optInt("page", parseInt(pg, 1));
        int pagecount = json.optInt("pagecount", page);
        int limit = parseInt(json.optString("limit", "20"), 20);
        result.put("page", page).put("pagecount", pagecount).put("limit", limit);
        if (json.has("total")) result.put("total", json.optInt("total"));
    }

    /** 每个一级分类的筛选：第一行为其下二级分类（key=t），其余共用 config 的 class/area/year 行 */
    private JSONObject filters() throws Exception {
        JSONObject result = new JSONObject();
        for (int i = 0; i < types.length(); i++) {
            JSONObject type = types.getJSONObject(i);
            JSONArray rows = new JSONArray();
            JSONArray values = new JSONArray().put(new JSONObject().put("n", "全部").put("v", "all"));
            JSONArray sub = type.optJSONArray("sub");
            if (sub != null) for (int j = 0; j < sub.length(); j++) {
                JSONObject item = sub.getJSONObject(j);
                values.put(new JSONObject().put("n", item.getString("name")).put("v", item.getString("id")));
            }
            rows.put(new JSONObject().put("key", "t").put("name", "分类").put("init", "all").put("value", values));
            for (int j = 0; j < filterRows.length(); j++) rows.put(filterRows.getJSONObject(j));
            result.put(type.getString("id"), rows);
        }
        return result;
    }

    private static String stripHtml(String text) {
        return text.replaceAll("<[^>]+>", "").trim();
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String error(String msg) throws Exception {
        return new JSONObject().put("msg", msg).put("list", new JSONArray()).toString();
    }
}
