# مشخصاتِ هستهٔ کلاینت (چندسکویی)

هدف: یک منطق، سه پوست. هسته مسئولِ اتصال است؛ UI فقط وضعیت را نشان می‌دهد.

## مسئولیت‌ها

1. گرفتنِ bootstrap و پروفایل از سرویس (با timeout و fallback).
2. ساختِ کانفیگِ هسته از پروفایل (بدونِ تغییرِ دستی؛ فقط جای‌گذاریِ مسیرِ tun و پورتِ proxy).
3. بالا/پایین آوردنِ تونل و tun.
4. سنجشِ سلامت با دو نوع پروب: از داخلِ تونل، و از مسیرِ مستقیمِ دستگاه.
5. مدیریتِ نردبان: `SAME_CONFIG → OTHER_BOX → FRESH_MULTI → REALITY_ONLY → CACHED_CDN → FRESH_CDN`.
6. حافظهٔ پله: پله‌ای که نشست را بالا آورد، دفعهٔ بعد اول امتحان می‌شود (با انقضای ۲۴ ساعته).
7. تلمتری: نتیجهٔ تلاش‌ها، پلهٔ موفق، خطاهای هسته.
8. آپدیت: بررسیِ امضا، دانلودِ رِنج‌محور، بازگشت‌پذیری.

## ماشینِ حالت

```
DISCONNECTED -> CONNECTING -> CONNECTED -> DEGRADED -> RECOVERING -> CONNECTED
                     |                        |
                     +------ FAILED <---------+
```
- `CONNECTED` فقط بعد از یک پروبِ موفقِ داخلِ تونل.
- `DEGRADED` = سه پروبِ پشتِ‌سرهم بدتر از baseline (بدونِ قطعِ نشست).
- `RECOVERING` از پله‌های نردبان استفاده می‌کند و هر پله‌ای که کار کند ذخیره می‌شود.

## اینترفیسِ پیشنهادی هسته (برای هر سه پلتفرم یکسان)

```kotlin
interface TunnelCore {
  suspend fun start(profileJson: String, tun: TunParams): Result<Unit>
  suspend fun stop()
  fun stats(): CoreStats            // bytes, uptime, lastHandshake
  suspend fun probe(url: String): Long   // ms, or -1
  val state: StateFlow<CoreState>
}
```
- اندروید: پیاده‌سازی روی `VpnService` + هستهٔ مشترک.
- ویندوز: سرویس + آداپتورِ tun (wintun).
- لینوکس: systemd + tun.
- تفاوت‌ها فقط در «ساختِ tun» است؛ بقیهٔ منطق در هستهٔ مشترک می‌ماند.

## سه پلتفرم، یک آزمون

هر تغییر در هسته فقط وقتی «انجام‌شده» است که روی هر سه پلتفرم:
1. با **همان** پروفایلِ stage وصل شود؛
2. `generate_204` = ۲۰۴ و `gemini/app` = ۲۰۰ بدهد؛
3. یک دانلودِ ۸MiB رِنج‌محور کامل شود.
