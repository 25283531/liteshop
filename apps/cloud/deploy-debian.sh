#!/usr/bin/env bash
set -Eeuo pipefail

# One-command Docker deployment for Debian 11/12 and Ubuntu 22.04/24.04.
# Run from the repository root: sudo bash apps/cloud/deploy-debian.sh

if [[ "${EUID}" -ne 0 ]]; then
  echo "请使用 sudo 运行此脚本" >&2
  exit 1
fi
if [[ ! -f apps/cloud/docker-compose.yml ]]; then
  echo "请在 LiteShop 仓库根目录运行此脚本" >&2
  exit 1
fi

export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y ca-certificates curl openssl docker.io docker-compose-plugin || \
  apt-get install -y ca-certificates curl openssl docker.io docker-compose
systemctl enable --now docker

if docker compose version >/dev/null 2>&1; then
  compose=(docker compose)
elif command -v docker-compose >/dev/null 2>&1; then
  compose=(docker-compose)
else
  echo "未找到 Docker Compose" >&2
  exit 1
fi

mkdir -p apps/cloud
if [[ ! -f apps/cloud/.env ]]; then
  terminal_token="$(openssl rand -hex 32)"
  miniapp_token="$(openssl rand -hex 32)"
  admin_token="$(openssl rand -hex 32)"
  admin_password="$(openssl rand -hex 16)"
  umask 077
  cat > apps/cloud/.env <<EOF
LITESHOP_TERMINAL_TOKEN=${terminal_token}
LITESHOP_MINIAPP_TOKEN=${miniapp_token}
LITESHOP_ADMIN_TOKEN=${admin_token}
LITESHOP_ADMIN_USERNAME=admin
LITESHOP_ADMIN_PASSWORD=${admin_password}
EOF
  echo "已生成 apps/cloud/.env，请妥善保存令牌。"
  echo "管理员账户：admin；密码已写入 apps/cloud/.env，请妥善保存。"
else
  echo "沿用已有 apps/cloud/.env"
  if ! grep -q '^LITESHOP_ADMIN_USERNAME=' apps/cloud/.env; then echo 'LITESHOP_ADMIN_USERNAME=admin' >> apps/cloud/.env; fi
  if ! grep -q '^LITESHOP_ADMIN_PASSWORD=' apps/cloud/.env; then echo "LITESHOP_ADMIN_PASSWORD=$(openssl rand -hex 16)" >> apps/cloud/.env; fi
fi

"${compose[@]}" --env-file apps/cloud/.env -f apps/cloud/docker-compose.yml up -d --build
curl --fail --silent --show-error http://127.0.0.1:8787/healthz
echo
echo "LiteShop Cloud API 已启动。请使用反向代理提供 HTTPS，再将终端同步地址配置为你的 HTTPS 域名。"
