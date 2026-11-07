# راهنمای کامل اتصال سرور، بک‌اند، کانفیگ‌ها و فایل نصبی (ویژه تیم فنی و سرور)

این پکیج کاملاً **خام (Production / Server-Only)** و بدون هیچ‌گونه داده نمایشی (Demo/Mock) آماده شده است. تمامی بخش‌های اپلیکیشن (ورود، ساخت پروفایل، دریافت لیست سرورها، پینگ TCP، محاسبه ترافیک زنده از هسته شبکه اندروید و همگام‌سازی حجم بسته) صددرصد وابسته به سرور هستند.

---

## ۱. فایل نصبی آماده (`.apk`) داخل همین فایل ZIP
در ریشه (Root) پروژه، فایل نصبی آماده قرار داده شده است:
- **`/ShieldVPN.apk`** (نسخه نهایی خام و متصل به سرور)
- **`/ShieldVPN-Production-ServerOnly.apk`** (کپی پشتیبان نسخه سرور)
- **`/app/build/outputs/apk/debug/app-debug.apk`** (خروجی استاندارد Gradle)

---

## ۲. فایل‌های سرور بک‌اند و نمونه کانفیگ‌های آماده در پوشه `/backend`

برای سرعت بخشیدن به کار تیم بک‌اند و زیرساخت، تمام فایل‌های سرور و کانفیگ‌ها در پوشه **`/backend`** داخل همین پروژه قرار داده شده‌اند:

1. **`/backend/server.js`** و **`/backend/package.json`**
   - سرور مرجع Node.js / Express که هر ۵ اندپوینت مورد نیاز اپلیکیشن (`auth/login`، `auth/register`، `user/profile`، `user/traffic-sync` و لینک اشتراک `sub/:token`) را دقیقاً مطابق قرارداد JSON اپلیکیشن پیاده‌سازی کرده است.
2. **`/backend/schema.sql`**
   - ساختار جداول دیتابیس (`users`، `vpn_nodes`، `traffic_logs`) قابل اجرا روی PostgreSQL و MySQL.
3. **`/backend/configs/xray-vless-reality-server.json`**
   - کانفیگ آماده سرور Xray-core برای پروتکل `VLESS + XTLS-Reality` روی پورت `443`.
4. **`/backend/configs/sing-box-server.json`**
   - کانفیگ آماده سرور Sing-box برای پروتکل `VLESS + Reality`.
5. **`/backend/configs/wireguard-wg0.conf`**
   - کانفیگ آماده سرور WireGuard (`wg0.conf`) روی پورت `51820`.

---

## ۳. فایل‌های کلیدی سورس اندروید برای تیم بک‌اند و هسته (Core)

1. **`app/src/main/java/com/example/model/VpnModels.kt`**
   - در کلاس `BackendEndpointConfig` آدرس پایه سرور (`apiBaseUrl = "https://api.shieldvpn-core.io/v1/"`)، پورت پیش‌فرض (`defaultPort = 443`)، پورت ساکس محلی (`localSocksPort = 10808`)، `tunnelMtu = 1500` و `primaryDns = "1.1.1.1"` تعریف شده است.
   - تیم شما می‌تواند `apiBaseUrl` را مستقیماً به دامنه سرور خود تغییر دهد.

2. **`app/src/main/java/com/example/network/VpnApiService.kt`**
   - شامل اینترفیس Retrofit (`ShieldBackendApi`) و ریپازیتوری (`VpnBackendRepository`).
   - **قانون امنیتی:** هیچ کاربری بدون دریافت پاسخ معتبر `HTTP 200 OK` به همراه `token` و `uid` از سرور اجازه ورود یا ثبت‌نام ندارد.

3. **`app/src/main/java/com/example/vpn/ShieldVpnService.kt`**
   - سرویس رسمی `android.net.VpnService` اندروید که در `AndroidManifest.xml` با مجوز `android.permission.BIND_VPN_SERVICE` ثبت شده است.
   - متد `onTunnelEstablished(tunFd, rawUri, host, port, protocol, credential)` محل دقیق اتصال موتور هسته (`libv2ray.so` / `Xray-core` / `Sing-box` / `WireGuard-Go`) به رابط شبکه (`tunFd`) است.

4. **`app/src/main/java/com/example/vpn/VpnUriParser.kt`**
   - مفسر لینک‌های سرور (`vless://`، `vmess://`، `trojan://`، `ss://`، `wg://`) و استخراج خودکار حجم بسته از هدر `Subscription-Userinfo`.

---

## ۴. قرارداد دقیق اندپوینت‌های REST API سرور

تمامی درخواست‌ها به آدرس پایه `apiBaseUrl` ارسال می‌شوند:

### ۱) ورود کاربر: `POST auth/login`
```json
{
  "email": "user@domain.com",
  "password": "user_password",
  "deviceName": "Android-ShieldClient"
}
```

### ۲) ساخت پروفایل جدید: `POST auth/register`
```json
{
  "displayName": "Ali Rezaei",
  "email": "user@domain.com",
  "password": "user_password"
}
```

### ۳) دریافت پروفایل، حجم بسته و لیست سرورهای کاربر: `GET user/profile`
- **Header:** `Authorization: Bearer <token>`

### ۴) ویرایش پروفایل: `PUT user/profile`
- **Header:** `Authorization: Bearer <token>`
```json
{
  "displayName": "Ali Rezaei",
  "email": "user@domain.com"
}
```

### ۵) همگام‌سازی مصرف حجم ترافیک: `POST user/traffic-sync`
- **Header:** `Authorization: Bearer <token>`
```json
{
  "uid": "usr_99281",
  "consumedSessionBytes": 1048576,
  "totalUsedBytes": 524288000,
  "activeNodeHost": "de.yourdomain.com",
  "activeNodePort": 443
}
```

### پاسخ استاندارد سرور (`ServerAuthResponse`) برای تمام اندپوینت‌های بالا:
```json
{
  "uid": "usr_99281",
  "displayName": "Ali Rezaei",
  "email": "user@domain.com",
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "planTitle": "Pro 50GB",
  "usedBytes": 1073741824,
  "totalBytes": 53687091200,
  "expiryDaysRemaining": 30,
  "subscriptionPayload": "vless://uuid@de.yourdomain.com:443?security=reality&type=tcp&sni=www.cloudflare.com#Germany - Frankfurt\ntrojan://pass@nl.yourdomain.com:443#Netherlands - Amsterdam"
}
```
