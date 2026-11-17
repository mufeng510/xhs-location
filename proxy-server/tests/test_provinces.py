from app.provinces import classify_province, all_provinces, FALLBACK


def test_municipality():
    assert classify_province("中国|北京市|北京市|电信|CN")[:2] == ("北京", "北京")
    assert classify_province("中国|上海市|上海市|移动|CN")[:2] == ("上海", "上海")
    assert classify_province("中国|重庆市|重庆市|联通|CN")[:2] == ("重庆", "重庆")
    assert classify_province("中国|天津市|天津市|电信|CN")[:2] == ("天津", "天津")


def test_province_with_autonomous_region_suffix():
    assert classify_province("中国|内蒙古自治区|呼和浩特市|电信|CN")[0] == "内蒙古"
    assert classify_province("中国|广西壮族自治区|南宁市|移动|CN")[0] == "广西"
    assert classify_province("中国|新疆维吾尔自治区|乌鲁木齐市|联通|CN")[0] == "新疆"
    assert classify_province("中国|西藏自治区|拉萨市|电信|CN")[0] == "西藏"
    assert classify_province("中国|宁夏回族自治区|银川市|电信|CN")[0] == "宁夏"


def test_common_provinces():
    assert classify_province("中国|浙江省|杭州市|电信|CN")[:2] == ("浙江", "杭州")
    assert classify_province("中国|黑龙江省|哈尔滨市|移动|CN")[0] == "黑龙江"
    assert classify_province("中国|陕西省|西安市|电信|CN")[0] == "陕西"
    assert classify_province("中国|湖北省|武汉市|电信|CN")[0] == "湖北"
    assert classify_province("中国|广东省|深圳市|电信|CN")[:2] == ("广东", "广州")


def test_fallback_on_garbage_or_overseas():
    assert classify_province("") == FALLBACK
    assert classify_province("United States|California|0|Google LLC|US") == FALLBACK
    assert classify_province("中国|0|0|移动|CN") == FALLBACK


def test_all_34_provinces_present():
    names = [p[0] for p in all_provinces()]
    assert len(names) == 34
    for required in ["北京", "上海", "广东", "内蒙古", "台湾", "香港"]:
        assert required in names
