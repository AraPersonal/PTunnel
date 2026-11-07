# شروعِ کار برای توسعه‌دهندهٔ موبایل/دسکتاپ

## چه چیزی را نباید دست بزنی

- `infra/` و `.github/` مالکِ زیرساخت دارد؛ تغییرشان با PR و بازبینیِ مالک است.
- هیچ سکرتی در ریپو نیست و نباید اضافه شود.

## راه‌اندازیِ محلی

```bash
git clone <repo> && cd ptunnel-core
make install && make test          # پنل: تست + اسکنِ سکرت
cd apps/android && ./gradlew :app:assembleDebug   # نیاز به Android SDK
```
- کورِ قدیمی (`libv2ray.aar`) عمداً در گیت نیست؛ تصمیمِ موتور در `docs/decisions/0005-client-core.md` است.
- برای تستِ اتصال بدونِ سرور: پنل را با `make dev` بالا بیاور؛ برای تونل به یک باکسِ stage نیاز است.

## چه چیزی را کجا اضافه کنی

| کار | جای فایل |
|---|---|
| افزودنِ یک ترانسپورت جدید | هستهٔ مشترک + یک ردیف در `docs/roadmap-core.md` + **عدد** تست در جدولِ نتایج |
| تغییر در منطقِ اتصال/نردبان | هستهٔ مشترک؛ UI فقط وضعیت را نمایش می‌دهد |
| مصرفِ API جدید | `docs/integration/protocol-contract.md` را اول به‌روز کن، بعد کد |
| متن/آیکن/رنگ | پوستهٔ UI همان پلتفرم |

## تعریفِ «تمام‌شده» برای هر PR

1. تستِ واحد برای منطقِ خالص (نردبان، انتخاب، پارس).
2. یک ردیف تازه در جدولِ نتایجِ `docs/roadmap-core.md` اگر رفتارِ شبکه عوض شده.
3. اگر تصمیمِ جدیدی گرفته شد: یک ADR کوتاه در `docs/decisions/`.
4. `make test` سبز و CI سبز.

## آزمونِ پذیرش (همان برای هر سه پلتفرم)

```bash
# از داخلِ دستگاه/کامپیوترِ وصل‌شده
curl -s -o /dev/null -w '%{http_code}\n' https://www.google.com/generate_204   # 204
curl -s -o /dev/null -w '%{http_code}\n' https://gemini.google.com/app          # 200
curl -s -o /dev/null -w '%{http_code} %{speed_download}\n' -r 0-8388607 https://<stage>/dl   # 206، ≥۵۰۰KB/s
```
