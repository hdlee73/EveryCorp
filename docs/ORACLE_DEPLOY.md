# Oracle Cloud Always Free 에 서버 올리기 (비용 0원)

목표: 백엔드(FastAPI)를 Oracle 무료 VM 한 대에 올리고, **고정 공인 IP**(키움 REST API 등록용)와 **HTTPS**(안드로이드 릴리스 앱 필수)를 갖춘다.

> 아래 화면 이름은 2026년 기준 OCI 콘솔 용어입니다. 메뉴 위치가 조금 달라졌다면 영문 이름으로 검색하세요.
> 무료 한도와 정책은 바뀔 수 있으니 [Oracle Always Free 문서](https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm)를 함께 확인하세요.

## 0. 준비물
- 해외 결제 가능한 신용/체크카드 (본인 확인용 소액 승인 후 취소됨. 무료 한도 안에서는 청구되지 않음)
- 이 레포의 API 키들 (DART, 공공데이터포털, 키움, KRX), 앱 토큰(임의의 긴 문자열)

## 1. 가입
1. https://www.oracle.com/kr/cloud/free/ → **무료로 시작하기**.
2. **Home Region 은 South Korea (Seoul 또는 Chuncheon)** 선택. 가입 후 변경 불가.
3. 카드 인증까지 끝나면 콘솔(cloud.oracle.com)에 들어갈 수 있습니다. 계정 활성화에 몇 분~몇 시간 걸릴 수 있습니다.

## 2. VM 만들기
콘솔 → **Compute → Instances → Create instance**
| 항목 | 값 |
|---|---|
| Image | **Canonical Ubuntu 22.04** (또는 24.04) |
| Shape | ① **VM.Standard.A1.Flex** (Ampere, 1 OCPU / 6GB 권장, 무료 한도는 합계 4 OCPU/24GB) — "Out of capacity" 가 나오면 시간대를 바꿔 재시도 ② 안 되면 **VM.Standard.E2.1.Micro** (AMD, 1GB — 4번의 스왑 설정 필수) |
| Networking | 기본 VCN 자동 생성, **Assign a public IPv4 address** 체크 |
| SSH keys | **Generate a key pair** 후 개인키(.key)를 안전한 곳에 다운로드 (분실하면 접속 불가) |
| Boot volume | 기본(50GB) |

## 3. 고정 IP 만들기 (키움 등록용)
1. **Networking → IP management → Reserved public IPs → Reserve public IP address** (이름 `everycorp-ip`).
2. 인스턴스 상세 → **Attached VNICs → Primary VNIC → IPv4 Addresses → Edit** → 기존 Ephemeral IP 를 **No public IP** 로 바꿔 저장 → 다시 Edit → **Reserved public IP** 로 방금 만든 IP 선택.
3. 이 IP 가 **키움 REST API 발급 화면에 입력할 IP**입니다. (예약 IP 는 인스턴스에 붙어 있는 동안 무료)

## 4. 포트 열기 (80, 443)
Oracle 은 **두 군데**를 모두 열어야 합니다.
1. 콘솔: **Networking → Virtual cloud networks → (VCN) → Security Lists → Default → Add Ingress Rules**
   - Source CIDR `0.0.0.0/0`, Protocol TCP, Destination port `80,443`
2. VM 안(Ubuntu 이미지는 iptables 가 기본 차단):
```bash
sudo iptables -I INPUT 6 -p tcp --dport 80 -j ACCEPT
sudo iptables -I INPUT 6 -p tcp --dport 443 -j ACCEPT
sudo apt-get install -y iptables-persistent   # 질문엔 Yes
sudo netfilter-persistent save
```

## 5. VM 접속과 초기 설정
```bash
chmod 600 ~/Downloads/ssh-key-*.key
ssh -i ~/Downloads/ssh-key-*.key ubuntu@<예약 IP>
```
(Windows 는 PowerShell 에서 같은 `ssh` 명령 사용 가능)

메모리가 1GB(E2.1.Micro)면 스왑부터:
```bash
sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile
sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
```
Docker 설치:
```bash
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker ubuntu && exit   # 다시 접속
```

## 6. 무료 도메인(HTTPS 용) — DuckDNS
Let's Encrypt 인증서는 IP 가 아닌 도메인이 필요합니다.
1. https://www.duckdns.org 에 GitHub 등으로 로그인 → 원하는 이름(예: `everycorp-hd`)을 **add domain**.
2. current ip 칸에 **예약 IP** 입력 → update ip. 도메인은 `everycorp-hd.duckdns.org`.

## 7. 서버 실행
```bash
git clone https://github.com/hdlee73/EveryCorp.git
cd EveryCorp/deploy
cp .env.example .env
nano .env          # DOMAIN 과 키 값 입력 (이 파일은 서버에만 있고 git 에는 올라가지 않음)
docker compose up -d --build
docker compose logs -f api   # 오류 확인 (Ctrl+C 로 빠져나옴)
```
확인: 브라우저에서 `https://everycorp-hd.duckdns.org/api/v1/health` → `{"status":"ok"...}`. 인증서 발급에 1~2분 걸릴 수 있습니다.

## 8. 앱에 서버 주소 연결
GitHub 레포 → Settings → Secrets and variables → Actions
- **Variables** → `API_BASE_URL` = `https://everycorp-hd.duckdns.org/` (슬래시로 끝)
- **Secrets** → `APP_TOKEN` = 서버 `.env` 의 `APP_TOKEN` 과 같은 값
그 뒤 Actions → Release APK → Run workflow 로 앱을 다시 빌드하면 새 APK 가 서버를 바라봅니다.

## 9. 업데이트·운영
```bash
cd ~/EveryCorp && git pull && cd deploy && docker compose up -d --build
```
- 로그: `docker compose logs --tail 100 api`
- 재부팅 후에도 `restart: unless-stopped` 로 자동 기동.
- **유휴 회수 주의:** Oracle 은 7일간 CPU 사용률이 아주 낮은 무료 VM 을 회수할 수 있다고 안내합니다. 앱 사용이 거의 없으면 해당될 수 있으니, 걱정되면 계정을 Pay As You Go 로 업그레이드하세요(무료 한도 안에서는 청구 없음, 예산 알림 설정 권장).
- 이 방식은 키를 서버의 `.env` 에 보관합니다. GitHub Actions 로 자동 배포하려면 SSH 개인키를 Secrets 에 넣는 구성이 추가로 필요하며, 원하시면 만들어 드립니다.
