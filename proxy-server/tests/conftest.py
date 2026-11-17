import os
import sys
import tempfile

# 测试环境：固定管理密码 + 独立临时数据库（session 级，防止污染开发数据）
_tmpdir = tempfile.mkdtemp(prefix="vlocation-test-")
os.environ.setdefault("ADMIN_PASSWORD", "test-admin-password-123")
os.environ["PROXY_DB"] = os.path.join(_tmpdir, "test.db")
os.environ["IP2REGION_XDB"] = os.path.join(_tmpdir, "missing.xdb")  # geoip 降级返回 None
os.environ["REFRESH_MINUTES"] = "20"

sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))
