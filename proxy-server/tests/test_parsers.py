import pytest

from app.proxy_sources import (
    parse_proxy_line,
    parse_proxy_lines,
    _parse_geonode_json,
    _parse_proxifly_json,
    _parse_proxyscrape_json,
    merge_candidates,
    ProxyCandidate,
)


class TestParseProxyLine:
    def test_socks5_with_country_tag(self):
        assert parse_proxy_line("socks5://222.211.216.201:7890 #cn") == (
            "222.211.216.201", 7890, "socks5", "cn")

    def test_bare_ip_port_defaults_socks5(self):
        assert parse_proxy_line("1.2.3.4:1080") == ("1.2.3.4", 1080, "socks5", None)

    def test_http_and_https(self):
        assert parse_proxy_line("http://1.2.3.4:8080")[2] == "http"
        assert parse_proxy_line("https://1.2.3.4:8080")[2] == "http"

    def test_rejects_socks4_bad_port_junk(self):
        assert parse_proxy_line("socks4://1.2.3.4:1080") is None
        assert parse_proxy_line("http://1.2.3.4:99999") is None
        assert parse_proxy_line("http://1.2.3.4:0") is None
        assert parse_proxy_line("not a line") is None
        assert parse_proxy_line("# comment") is None
        assert parse_proxy_line("") is None


class TestParseProxyLines:
    TEXT = "socks5://1.1.1.1:1080 #cn\nsocks5://2.2.2.2:1080 #us\n3.3.3.3:1080\n"

    def test_cn_filter_keeps_cn_and_untagged(self):
        out = parse_proxy_lines(self.TEXT, country_filter="cn")
        assert [x[0] for x in out] == ["1.1.1.1", "3.3.3.3"]

    def test_no_filter_keeps_supported(self):
        out = parse_proxy_lines(self.TEXT)
        assert len(out) == 3  # socks4 不应出现在该样本文本中，但也应被过滤测试覆盖


def _node(ip, port=1080):
    return ProxyCandidate(ip, port, "socks5", "t", 0.0, 0.0, "其他地区")


class TestMergeCandidates:
    def test_round_robin(self):
        a = [_node("1.1.1.1"), _node("1.1.1.2"), _node("1.1.1.3")]
        b = [_node("2.2.2.1"), _node("2.2.2.2")]
        merged = merge_candidates([a, b], 10)
        assert [c.server for c in merged] == ["1.1.1.1", "2.2.2.1", "1.1.1.2", "2.2.2.2", "1.1.1.3"]

    def test_dedupe_ip_port(self):
        a = [_node("1.1.1.1"), _node("1.1.1.2")]
        b = [_node("1.1.1.1"), _node("2.2.2.1")]
        merged = merge_candidates([a, b], 10)
        assert [c.server for c in merged] == ["1.1.1.1", "1.1.1.2", "2.2.2.1"]

    def test_limit(self):
        a = [_node(f"1.1.1.{i}") for i in range(50)]
        b = [_node(f"2.2.2.{i}") for i in range(50)]
        assert len(merge_candidates([a, b], 60)) == 60

    def test_empty(self):
        assert merge_candidates([], 10) == []
        assert merge_candidates([[], []], 10) == []


class TestGeonodeJson:
    ITEM = '{"ip":"113.116.227.59","port":"9000","protocols":["http"],"city":"Shenzhen","upTime":100.0}'
    SOCKS = '{"ip":"39.68.66.152","port":"1080","protocols":["socks5"],"city":"Jinan","upTime":97.3}'

    def test_bare_array(self):
        out = _parse_geonode_json(f"[{self.ITEM}, {self.SOCKS}, {{\"ip\":\"1.2.3.4\",\"port\":\"1080\",\"protocols\":[\"socks4\"]}}]")
        assert len(out) == 2
        assert out[0].server == "113.116.227.59"  # upTime 降序
        assert out[1].type == "socks5"

    def test_envelope(self):
        out = _parse_geonode_json('{"total": 2, "data": [%s, %s]}' % (self.ITEM, self.SOCKS))
        assert len(out) == 2

    def test_garbage(self):
        assert _parse_geonode_json("not json") == []
        assert _parse_geonode_json("") == []
        assert _parse_geonode_json('{"total": 0}') == []


class TestProxiflyJson:
    def test_score_sort_and_socks4_skip(self):
        text = """[
          {"protocol":"socks5","ip":"1.1.1.1","port":1080,"score":3,"geolocation":{"country":"CN","city":"Foshan"}},
          {"protocol":"http","ip":"2.2.2.2","port":8080,"score":9,"geolocation":{"country":"CN","city":"Hangzhou"}},
          {"protocol":"socks4","ip":"3.3.3.3","port":1080,"score":10,"geolocation":{"country":"CN","city":""}}
        ]"""
        out = _parse_proxifly_json(text)
        assert [c.server for c in out] == ["2.2.2.2", "1.1.1.1"]
        assert out[0].type == "http"


class TestProxyScrapeJson:
    def test_uptime_sort(self):
        text = """[
          {"protocol":"socks5","ip":"1.1.1.1","port":1080,"uptime_percent":26.4,"city":"Chengdu"},
          {"protocol":"http","ip":"2.2.2.2","port":8085,"uptime_percent":98.4,"city":"Haikou"}
        ]"""
        out = _parse_proxyscrape_json(text)
        assert [c.server for c in out] == ["2.2.2.2", "1.1.1.1"]
