# راهنمای فنی پیاده‌سازی مکانیزم‌های ضد فیلترینگ و بقا

این سند نحوه پیاده‌سازی تکنیک‌های مصوب در [ADR-0006](file:///c:/Users/rahati.amirreza/Documents/PTunnel/docs/decisions/0006-resilient-transports-and-anti-throttling.md) را به همراه تنظیمات و نمونه کدها تشریح می‌کند.

---

## ۱. پرش پورت (Port Hopping) برای Hysteria 2

### ۱.۱. تنظیم سمت سرور (Linux iptables / nftables)
برای اینکه سرور بسته‌های ورودی UDP روی بازه پورت وسیع (مثلاً ۲۰۰۰۰ تا ۵۰۰۰۰) را دریافت کند و به پورت Hysteria 2 (مثلاً ۴۴۳) تحویل دهد:

```bash
# هدایت بازه پورت به پورت محلی هیستریا (۴۴۳)
iptables -t nat -A PREROUTING -p udp --dport 20000:50000 -j REDIRECT --to-ports 443

# اگر فایروال UFW فعال است:
ufw allow 20000:50000/udp
```

برای دائمی کردن در سرور اوبونتو/دبیان:
```bash
apt-get install -y iptables-persistent
netfilter-persistent save
```

### ۱.۲. تنظیم سمت کلاینت
کلاینت آدرس سرور را به صورت بازه پورت یا لیست پورت مشخص می‌کند:
```yaml
server: 38.17.64.159:20000-50000
# یا کلاینت با تایمر هر ۴۵ ثانیه پورت را در این بازه رندوم تغییر می‌دهد
```

---

## ۲. تکه‌تکه کردن TLS (TLS Fragmentation) در Xray

سیستم فیلترینگ DPI ایران هنگام بازرسی بسته `ClientHello`، بایت‌های نام دامنه سرور را می‌خواند. تنظیم زیر در بخش `outbounds` کلاینت Xray بسته را به قطعات ریز خرد می‌کند تا فایروال نتواند آن را بازسازی کند:

```json
{
  "outbounds": [
    {
      "tag": "proxy",
      "protocol": "vless",
      "settings": {
        "vnext": [
          {
            "address": "38.17.64.159",
            "port": 8080,
            "users": [
              {
                "id": "YOUR_UUID",
                "encryption": "none",
                "flow": "xtls-rprx-vision"
              }
            ]
          }
        ]
      },
      "streamSettings": {
        "network": "tcp",
        "security": "reality",
        "realitySettings": {
          "serverName": "www.microsoft.com",
          "fingerprint": "chrome",
          "show": false,
          "publicKey": "YOUR_PUBLIC_KEY",
          "shortId": "YOUR_SHORT_ID"
        },
        "sockopt": {
          "dialerProxy": "fragment"
        }
      }
    },
    {
      "tag": "fragment",
      "protocol": "freedom",
      "settings": {
        "domainStrategy": "AsIs",
        "fragment": {
          "packets": "1-3",
          "length": "100-200",
          "interval": "10-20"
        }
      }
    }
  ]
}
```
* **packets: "1-3"**: بسته‌های شماره ۱ تا ۳ (هندشیک اولیه TLS) را هدف قرار می‌دهد.
* **length: "100-200"**: اندازه هر چانک به ۱۰۰ تا ۲۰۰ بایت محدود می‌شود.
* **interval: "10-20"**: ایجاد وقفه ۱۰ تا ۲۰ میلی‌ثانیه‌ای بین چانک‌ها که مانع از بافر شدن آن در فایروال می‌شود.

---

## ۳. پروتکل AmneziaWG (WireGuard مبهم‌شده)

### ۳.۱. تفاوت ساختاری با WireGuard رسمی
وایرگارد رسمی دارای ۴ پیام با ساختار مشخص است:
* `Type 1`: Handshake Initiation (بایت اول = 1)
* `Type 2`: Handshake Response (بایت اول = 2)
* `Type 3`: Cookie Reply (بایت اول = 3)
* `Type 4`: Data Packet (بایت اول = 4)

فایروال ایران با مشاهده بایت اول و سایز ثابت پیام‌ها اتصال را مسدود می‌کند. **AmneziaWG** این مقادیر را تغییر می‌دهد:

```ini
[Interface]
Address = 10.7.0.2/24
PrivateKey = YOUR_CLIENT_PRIVATE_KEY
DNS = 1.1.1.1
# پارامترهای ضد فیلترینگ اختصاصی AmneziaWG:
Jc = 4                  # تعداد پکت‌های آشغال پیش از هندشیک
Jmin = 40               # حداقل اندازه پکت‌های رندوم (بایت)
Jmax = 70               # حداکثر اندازه پکت‌های رندوم (بایت)
S1 = 65                 # آفست رندوم برای پیام نوع ۱
S2 = 92                 # آفست رندوم برای پیام نوع ۲
H1 = 1204859231         # مجیک هدر جایگزین پیام نوع ۱
H2 = 839201948          # مجیک هدر جایگزین پیام نوع ۲
H3 = 1928472910         # مجیک هدر جایگزین پیام نوع ۳
H4 = 492018471          # مجیک هدر جایگزین پیام نوع ۴ (دیتا)

[Peer]
PublicKey = YOUR_SERVER_PUBLIC_KEY
Endpoint = 38.17.64.159:51820
AllowedIPs = 0.0.0.0/0
PersistentKeepalive = 25
```

---

## ۴. نردبان نجات CDN با Clean IP (کلودفلر)

در زمان اعمال محدودیت اینترنت ملی که تمام ارتباطات مستقیم با آی‌پی‌های سرور خارجی فیلتر می‌شود:
1. دامنه بر روی کلاودفلر (پراکسی روشن ☁️) تنظیم می‌شود.
2. ترافیک با وب‌سوکت رمزنگاری‌شده (`VLESS over WS + TLS`) ارسال می‌شود.
3. کلاینت به جای Resolve کردن دامنه، مستقیماً به یکی از آی‌پی‌های تمیز لیست زیر متصل شده و نام دامنه را در هدر `Host` و `SNI` ارسال می‌کند:

```
188.114.96.3
188.114.97.3
104.17.2.81
104.16.132.229
162.159.138.85
```
این قابلیت از قبل در سرور با اندپوینت `GET /c` و در کلاینت در بخش `CDN_IP_URLS` تعبیه شده است.

---

## ۵. پروتکل ShadowTLS v3

برای شرایطی که بازرسی فعال (Active Probing) دامنه‌های Reality را شناسایی و مسدود کند:
* سرور ShadowTLS ترافیک ورودی را به یک دامنه مطمئن (مانند `captive.apple.com`) فوروارد می‌کند.
* اگر ربات فیلترینگ پورت را اسکن کند، گواهینامه رسمی و رفتار طبیعی اپل را دریافت می‌کند.
* کلاینت مجاز با ارسال رمز مشترک در فیلد SessionID یا هدر TLS، اتصال را به پورت Shadowsocks داخلی هدایت می‌کند.
