#!/usr/bin/env bash
# =============================================================================
# scripts/aws/bootstrap.sh — EC2 my-macro-app(Ubuntu 24.04 LTS arm64)의 첫 준비(ADR 0019). 인스턴스를 만들 때 user-data로
# 넣으면 첫 기동 때 root로 한 번 돕니다. 기록은 /var/log/cloud-init-output.log. 다시 돌려도 됩니다(된 단계는 건너뜀).
#
#   · apt 갱신(보안 패치)
#   · Docker 공식 저장소의 docker-ce + compose·buildx 플러그인(Ubuntu 기본 저장소의 패키지가 아님)
#   · AWS CLI v2(공식 설치 파일 — Ubuntu 24.04 저장소에는 awscli 패키지가 없음), git·make·unzip
#   · 2 GiB 스왑 파일 — 4 GiB에서 이미지 빌드가 메모리를 넘길 때 OOM 대신 느려지게
#   · journald 상한(200M) — 30 GB 디스크
#   · ubuntu 사용자를 docker 그룹에(SSM 셸에서 `sudo -iu ubuntu` 뒤 docker를 sudo 없이)
# SSM 에이전트는 Canonical AMI에 snap으로 들어 있어 따로 깔지 않습니다. 비밀값은 다루지 않습니다.
# =============================================================================
set -euo pipefail

[ "$(id -u)" = 0 ] || { echo "root로 실행하세요(user-data는 root로 돕니다)." >&2; exit 1; }
export DEBIAN_FRONTEND=noninteractive
APT_OPTS=(-y -q -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold)

echo "== apt 갱신"
apt-get update -q
apt-get upgrade "${APT_OPTS[@]}"
apt-get install "${APT_OPTS[@]}" ca-certificates curl git make unzip

echo "== Docker (공식 저장소)"
if ! command -v docker >/dev/null || ! docker compose version >/dev/null 2>&1; then
    install -m 0755 -d /etc/apt/keyrings
    curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
    chmod a+r /etc/apt/keyrings/docker.asc
    # Docker 문서(docs.docker.com/engine/install/ubuntu)의 deb822 형식 그대로
    # shellcheck disable=SC1091
    codename="$(. /etc/os-release && echo "${UBUNTU_CODENAME:-$VERSION_CODENAME}")"
    cat > /etc/apt/sources.list.d/docker.sources <<EOF
Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: $codename
Components: stable
Architectures: $(dpkg --print-architecture)
Signed-By: /etc/apt/keyrings/docker.asc
EOF
    apt-get update -q
    apt-get install "${APT_OPTS[@]}" docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
fi
systemctl enable --now docker
id -nG ubuntu | grep -qw docker || usermod -aG docker ubuntu

echo "== AWS CLI v2"
if ! command -v aws >/dev/null; then
    tmp="$(mktemp -d)"
    curl -fsSL "https://awscli.amazonaws.com/awscli-exe-linux-$(uname -m).zip" -o "$tmp/awscliv2.zip"
    unzip -q "$tmp/awscliv2.zip" -d "$tmp"
    "$tmp/aws/install"
    rm -rf "$tmp"
fi
# 서버 셸에서 치는 aws s3 …가 리전을 묻지 않게(서울). 인증은 인스턴스 프로필이 맡아 키는 두지 않습니다.
if [ ! -f /home/ubuntu/.aws/config ]; then
    install -d -m 700 -o ubuntu -g ubuntu /home/ubuntu/.aws
    printf '[default]\nregion = ap-northeast-2\n' > /home/ubuntu/.aws/config
    chown ubuntu:ubuntu /home/ubuntu/.aws/config
fi

echo "== 스왑 2 GiB"
if [ ! -f /swapfile ]; then
    fallocate -l 2G /swapfile
    chmod 600 /swapfile
    mkswap /swapfile >/dev/null
fi
swapon --show=NAME --noheadings | grep -qx /swapfile || swapon /swapfile
grep -q '^/swapfile ' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab

echo "== journald 상한"
mkdir -p /etc/systemd/journald.conf.d
cat > /etc/systemd/journald.conf.d/my-macro.conf <<'EOF'
[Journal]
SystemMaxUse=200M
EOF
systemctl restart systemd-journald

echo "== 끝: $(docker --version) · $(docker compose version --short) · $(aws --version 2>&1 | cut -d' ' -f1) · 스왑 $(swapon --show=SIZE --noheadings | head -n 1)"
