# YaranTV برای اندروید

نسخهٔ بومی اندروید YaranTV یک پوستهٔ سبک WebView برای نشانی زیر است:

https://yaran.info/tv/admin

## فناوری‌ها
- Kotlin و AndroidX
- حداقل نسخهٔ اندروید: Android 8.0 (API 26)
- رابط فارسی و راست‌به‌چپ
- پوستهٔ بنفش و SplashScreen بومی
- WebView با JavaScript، DOM Storage و کوکی‌های پایدار
- بارگذاری فایل با انتخاب‌گر بومی اندروید
- دریافت فایل با DownloadManager
- تازه‌سازی با کشیدن صفحه، دکمهٔ بازگشت و صفحهٔ خطای فارسی
- فونت محلی Vazirmatn با مجوز SIL Open Font License

## ساخت محلی
به JDK 17 و Android SDK شامل platform 35 نیاز است.

\`\`\`bash
./gradlew testDebugUnitTest lintDebug assembleDebug
\`\`\`

APK آزمایشی:
\`app/build/outputs/apk/debug/app-debug.apk\`

## ساخت خودکار
گردش‌کار GitHub Actions در \`.github/workflows/android-build.yml\` با push، pull request و اجرای دستی فعال می‌شود. فایل APK و checksum در artifact با نام \`YaranTV-Android-APK\` منتشر می‌شوند.

## نکات امنیتی
- اعتبارسنجی گواهی TLS غیرفعال یا دور زده نمی‌شود.
- کوکی‌ها و داده‌های WebView پاک نمی‌شوند و در لاگ ثبت نمی‌شوند.
- هیچ JavaScript interface بومی در اختیار صفحه قرار نمی‌گیرد.
- فایل‌های HTTP ناامن از طریق DownloadManager دریافت نمی‌شوند.
- مجوز نوشتن حافظه فقط برای Android 8 و 9 و جهت ذخیره در پوشهٔ عمومی Downloads تعریف شده است.
