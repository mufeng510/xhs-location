# vlocation 代理池服务
# 配置只有一个必填项：ADMIN_PASSWORD（管理密码，.env 提供）
# 注：本机已验证 python:3.13-slim（仓库同版本）；base 镜像若拉不动，
#    可用 --build-arg PIP_INDEX_URL=<镜像源> 换 pip 源（如清华源）
FROM python:3.13-slim

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    TZ=Asia/Shanghai \
    PROXY_DB=/data/proxy.db \
    IP2REGION_XDB=/data/ip2region_v4.xdb

WORKDIR /srv/proxy-server

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# ip2region 离线归属地库（11MB），固定版本 + SHA256 校验
ARG IP2REGION_URL=https://raw.githubusercontent.com/lionsoul2014/ip2region/master/data/ip2region_v4.xdb
ARG IP2REGION_SHA256=a12ad5aca1277d67e525efcda531a20cc0d654ff4f087150d45bc17fca4bc260
RUN mkdir -p /data \
    && curl -fsSL -o /data/ip2region_v4.xdb "$IP2REGION_URL" \
    && if [ -n "$IP2REGION_SHA256" ]; then \
         echo "$IP2REGION_SHA256  /data/ip2region_v4.xdb" | sha256sum -c -; \
       fi

COPY requirements.txt .
ARG PIP_INDEX_URL=https://pypi.org/simple
RUN pip install --no-cache-dir --index-url "$PIP_INDEX_URL" -r requirements.txt

COPY app ./app

EXPOSE 8080
VOLUME ["/data"]

HEALTHCHECK --interval=60s --timeout=5s --start-period=90s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8080/healthz', timeout=3).read()" || exit 1

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8080"]
