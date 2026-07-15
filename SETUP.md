# إعداد مستودع Cloudstream على GitHub

## الخطوات

### 1. إنشاء مستودع جديد على GitHub

1. اذهب إلى https://github.com/new
2. أدخل اسم المستودع: `my-extensions-repo`
3. اختر "Public"
4. اضغط "Create repository"

### 2. رفع الملفات

```bash
cd C:\Users\yemen\Desktop\cloudstream\my-extensions-repo
git init
git add .
git commit -m "Initial commit"
git branch -M main
git remote add origin https://github.com/YOUR_USERNAME/my-extensions-repo.git
git push -u origin main
```

### 3. بناء الإضافة

```bash
cd C:\Users\yemen\Desktop\cloudstream\SexAlArab
gradlew.bat build
```

### 4. إنشاء إصدار جديد

1. اذهب إلى صفحة المستودع على GitHub
2. اضغط على "Releases" ثم "Create a new release"
3. أدخل_tag: `v1.0.0`
4. أدخل العنوان: `Version 1.0.0`
5. ارفع ملف `.cs3` من `SexAlArab\build\outputs\`
6. اضغط "Publish release"

### 5. تحديث index.min.json

حدّث الملف بالرابط الصحيح:

```json
[
  {
    "url": "https://github.com/YOUR_USERNAME/my-extensions-repo/releases/download/v1.0.0/SexAlArab.cs3",
    "status": 1,
    "version": 1,
    "apiVersion": 1,
    "name": "سكس العرب",
    "internalName": "SexAlArab",
    "authors": ["YOUR_USERNAME"],
    "description": "إضافة سكس العرب",
    "repositoryUrl": "https://github.com/YOUR_USERNAME/my-extensions-repo",
    "tvTypes": ["NSFW"],
    "language": "ar",
    "iconUrl": "https://raw.githubusercontent.com/YOUR_USERNAME/my-extensions-repo/main/icons/SexAlArab.png",
    "fileSize": 0,
    "fileHash": ""
  }
]
```

### 6. تحديث index.json

حدّث الملف بالرابط الصحيح:

```json
{
  "iconUrl": "https://raw.githubusercontent.com/YOUR_USERNAME/my-extensions-repo/main/icon.svg",
  "name": "My Cloudstream Extensions",
  "description": "مستودع الإضافات الخاصة لـ Cloudstream",
  "manifestVersion": 1,
  "pluginLists": [
    "https://raw.githubusercontent.com/YOUR_USERNAME/my-extensions-repo/main/index.min.json"
  ]
}
```

### 7. إضافة المستودع إلى Cloudstream

1. افتح Cloudstream
2. اذهب إلى Settings > Extensions
3. اضغط على "Add Repository"
4. أدخل هذا الرابط:
```
https://raw.githubusercontent.com/YOUR_USERNAME/my-extensions-repo/main/index.json
```
5. اضغط "OK"
6. اذهب إلى Extensions وثبّت "سكس العرب"

## ملاحظات مهمة

- استبدل `YOUR_USERNAME` باسم المستخدم الخاص بك على GitHub
- تأكد من أن الملفات محفوظة كـ Public
- عند تحديث الإضافة، قم بزيادة `version` في `index.min.json`
- تأكد من رفع ملف `.cs3` الصحيح في كل إصدار
