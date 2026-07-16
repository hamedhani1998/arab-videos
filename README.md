# My Cloudstream Extensions

مستودع الإضافات الخاصة لـ Cloudstream

## التثبيت

1. افتح Cloudstream
2. اذهب إلى Settings > Extensions
3. اضغط على "Add Repository"
4. أدخل هذا الرابط:
```
https://raw.githubusercontent.com/hamedhani1998/my-extensions-repo/main/index.json
```
5. اضغط "OK"
6. اذهب إلى Extensions وثبّت الإضافات المتاحة

## الإضافات المتاحة

| الإضافة | الوصف | الحالة |
|---------|-------|--------|
| أفلامك1 | أفلام إباحية عربية | ✅ |
| ArabX | أفلام إباحية عربية | ✅ |
| العربدة | أفلام إباحية عربية | ✅ |
| سكس العرب | أفلام إباحية عربية مترجمة | ✅ |

## للمساهمة

1. Fork هذا المستودع
2. أضف إضافتك الجديدة
3. أرسل Pull Request

## للتطوير

### بناء الإضافة

```bash
cd SexAlArab
gradle build
```

### رفع الإصدار الجديد

1. قم ببناء الإضافة
2. أنشئ إصدار جديد في GitHub Releases
3. ارفع ملف `.cs3` كمرفق
4. حدّث `index.min.json` بالإصدار الجديد
