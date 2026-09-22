# دليل زيارة الشركة — تجربة أجهزة البصمة على الطبيعة

<!-- markdownlint-configure-file { "MD033": { "allowed_elements": ["div", "span"] } } -->

<!--
This file is Arabic and renders right to left on GitHub. GitHub gives every heading,
paragraph and list dir="auto", so each one takes its direction from its first letter.
When editing it:
- Keep everything below the title inside <div dir="rtl"> ... </div>; tables take their
  direction from it. The title stays above it for markdownlint, and starts in Arabic.
- Start every heading, every paragraph and the first item of every list with an Arabic
  word, not an English word or a code span ("في **Terminal 2**:", not "**Terminal 2:**").
  Later items in a list follow the list's direction, so they may start in English.
- Leave no blank line inside a list, and put no code block or table in one. A list
  with either shows its numbers or bullets on the left. For numbered steps that need a
  code block, write the number in bold at the start of a paragraph: **1)**, **2)**.
- Keep Arabic out of code blocks, comments included. Explain a command in the text above
  it, and write placeholders in English (SN, DEVICE-IP). In a code span, Arabic appears
  only where it quotes program output word for word.
- Wrap in <span dir="ltr">...</span> an inline code span that starts or ends with
  punctuation, starts with a digit without being a plain number, or holds Arabic:
  `--udp`, `field-report/`, `*.request.bin`, `1_attlog.dat`, `2026-09-16 08:01:02`.
  Plain numbers such as `8081`, `0.1.0` or `192.168.1.57/24` need no wrapping. Wrap a
  date or a signed number in plain text the same way (2026-09-16, +02:00). Otherwise
  those characters are drawn on the wrong side of the text.
-->

<div dir="rtl">

الدليل ده بيقولك تعمل إيه بالترتيب لما تروح شركة عندها أجهزة بصمة: تكتب إيه،
تبص على إيه، ولو حصلت مشكلة تعمل إيه. مش لازم تكون عارف نوع الجهاز قبل ما
تروح؛ الخطوات نفسها هتعرّفك.

- **الهدف من الزيارة:** نتأكد إن الكود اللي عملناه شغال مع جهاز حقيقي، ونسجّل
  معلومات الجهاز (الموديل، الـ firmware، طريقة إرسال البيانات) عشان نكمّل عليها.
- **الأوامر:** افتح 3 terminals على اللابتوب وسيبهم مفتوحين طول الزيارة. كل
  block أوامر في الدليل مكتوب فوقه هيتكتب في أنهي terminal:
  - الأول، **Terminal 1**، في فولدر الريبو `hr-platform`: للأوامر اللي بتبدأ بـ
    <span dir="ltr">`scripts/`</span> أو `git` أو `deploy` **بس**.
  - التاني، **Terminal 2**، في `hr-platform/devices-agent`: **لكل حاجة تانية**
    (`python3 -m workin_devices`، و `cp` و `chmod` و `rm`، وأي حاجة فيها
    <span dir="ltr">`field-report/`</span>).
  - التالت، **Terminal 3**، في `hr-platform/devices-agent` برضه: للـ `capture` **بس**،
    لأنه بيفضل شغال ومش بيرجعلك الـ prompt.
  - الأوامر اللي بتكتب أو بتمسح باسورد أو توكن بتتأكد هي في أنهي terminal. لو
    طبعت `WRONG TERMINAL`، ماحصلش حاجة: اكتبها تاني في الـ terminal اللي مكتوب.
  - فولدر <span dir="ltr">`field-report/`</span> لازم يكون **جوه `devices-agent`**. git متظبط يتجاهله
    هناك بس؛ لو اتعمل في `hr-platform` نفسه، التوكن والباسوردات هتظهر في
    `git status` وممكن تدخل في commit بالغلط.
- **مين بيعمل إيه:** إنت (اللي رايح الزيارة) بتنفذ الخطوات وبتكتب النتايج.
  أي تغيير في داتابيز البرود **صاحب الريبو (repository owner) بس** اللي يعمله أو
  يوافق عليه صراحة، وأي agent session (AI) ممنوع تعمله (الخطوة 12.1).
- **تفاصيل الـ agent وأوامره:** [on-prem-agent.md](on-prem-agent.md).
  **السميولشن:** [devices-lab.md](devices-lab.md).

## الطريق السريع: أمر واحد بيعمل الزيارة كلها

بعد ما تجهّز اللابتوب (الخطوة 1.2)، اكتب في **Terminal 2** (`devices-agent`):

```bash
python3 -m workin_devices visit
```

- الأمر ده بيمشي معاك في الخطوات من 2 لـ 10 بالترتيب، وبيسألك بس عن اللي
  مايقدرش يشوفه لوحده، وإنت بتجاوب **برقم** (1 أو 2 ...).
- هو اللي بيدوّر على الجهاز (بعد ما يسألك العميل وافق ولا لأ)، ويعمل النسخة
  الاحتياطية، ويشغّل الـ capture، ويخصّص الجهاز في اللاب، ويعمل تجارب البصمات
  ويحسب وصلت في كام ثانية، ويعرف رقم الدخول والخروج (من بصمة دخول وبصمة خروج)،
  ويبعت بالـ agent، ويرفع ملف الـ USB.
- **في الآخر** بيقولك النتيجة: ✅ كله تمام، أو ❌ كل مشكلة وحلها. وبيكتب تقرير في
  `field-report/visit-SN-DATE.md` فيه ورقة النتائج (آخر الدليل) متملية، من غير
  أكواد موظفين ولا أسامي. حطه في الـ issue (الخطوة 11).
- **زيارة ماعملتش أي فحص مش ✅.** لو العميل مارضاش على الشبكة ومافيش ملف USB، أو
  الزيارة اتوقفت في النص، التقرير بيتكتب بـ ❓ أو ⛔ وبيقول إن مافيش حاجة اتفحصت.
  التقرير ده بيتحط في issue والسطر الأول بيتقرا على إنه إجابة عن الموديل.
- **جهاز الـ capture مش فاهم سطوره**: لو الجهاز بيرفع سجلات بشكل سطر السيستم
  مش عارفه، الأمر مش هيحسب "بصمة وصلت في كام ثانية" أصلاً -- أي رفعة ممكن تكون
  سجل قديم مش البصمة اللي لسه اتعملت -- وهيكتب في الورقة **مااتقاسش** ويطلب شكل
  السطر في الـ issue. ده أهم اللي هيطلع من الزيارة دي.
- **وضع A بس** (اللاب). وضع B (البرود) بالخطوات اليدوية في الخطوة 12، وصاحب الريبو
  بس.
- جهاز واحد في كل مرة: لجهاز تاني شغّل الأمر تاني.
- لو عايز تعمل خطوة بإيدك، أو حصلت حاجة الأمر مش فاهمها، الخطوات اليدوية تحت زي
  ما هي، والقواعد (الخطوة 0) هي هي.

---

## 0. قواعد ممنوع تكسرها

الجهاز ده عليه حضور وانصراف موظفين الشركة ومرتباتهم. أي غلطة فيه مشكلة حقيقية
للعميل.

1. **ممنوع تمسح أي حاجة من الجهاز**: لا سجلات حضور، ولا موظفين، ولا بصمات، ولا
   admins. الـ agent بتاعنا أصلاً مايقدرش يمسح؛ وإنت كمان ماتعملش ده من منيو
   الجهاز.
2. **ممنوع تغيّر تاريخ أو ساعة أو time zone الجهاز**، إلا لو العميل طلب منك
   تصلّحها، وساعتها اكتب القيمة القديمة الأول.
3. **قبل ما تغيّر أي إعداد: صوّره بالموبايل.** وقبل ما تمشي رجّع كل حاجة زي
   الصورة (الخطوة 10).
4. **استأذن قبل ما توصّل اللابتوب على شبكتهم أو تعمل scan.**
5. **ابعد عن مواعيد الحضور والانصراف** (أول ساعة وآخر ساعة في الشيفت).
6. **الملفات اللي هتطلع من الزيارة فيها أكواد موظفين ومواعيد**: خليها على
   اللابتوب بس. ماتحطهاش في الريبو ولا في شات ولا في issue.
7. إحنا **مش بناخد بصمات الصوابع ولا صور الوش**. لو الجهاز بعتها، السيستم بيرميها.
   والـ `capture` بيكتب على اللابتوب (وبيطبع على الشاشة) **بس** اللي السيستم بيحتفظ
   بيه، وبالشكل اللي السيستم بيفهمه:
   - **أجهزة ZKTeco:** سطور الحضور (`ATTLOG`: كود، وقت، وحقول قصيرة)، والإعدادات
     (`OPTIONS`)، وسطور العمليات (`OPLOG`)، ونتايج الأوامر. سطر حضور السيستم
     مايفهموش بيتكتب **شكله بس**: <span dir="ltr">`# unparsed line shape: 9999,9999-99-99 ...`</span> (كل
     رقم `9` وكل حرف `a`)، عشان تعرف الفاصل والطول من غير القيم.
   - **أي ماركة تانية:** شكل الـ JSON أو XML بس: أسامي الحقول ونوع كل قيمة وطولها
     (<span dir="ltr">`"<string 9>"`</span>)، وأكواد الأحداث والحالة (زي `minor`؛ أي رقم تاني زي `pin` بيتكتب
     <span dir="ltr">`"<number>"`</span>) والتواريخ، وقيم دخول/خروج وطريقة التحقق (زي
     <span dir="ltr">`"attendanceStatus": "checkIn"`</span>). **مفيش أسامي ولا أرقام كروت ولا أكواد موظفين.**
   - أي حاجة تانية (بصمات، صور، بيانات تسجيل الموظفين، البطاقات، مهما كان اسمها)
     **مابتتكتبش خالص**.
   - في ملف الـ <span dir="ltr">`.json`</span> بتاع الطلب هتلاقي `request_body_withheld`: نوع اللي اتشال
     وحجمه بس (أو `structure only`). لو لقيت في <span dir="ltr">`field-report/`</span> صورة أو بصمة لأي
     سبب، امسحها وماتنقلهاش.

---

## 1. قبل الزيارة بيوم

### 1.1 اسأل العميل

- عنده كام جهاز، ونوعهم إيه، وفين.
- **باسورد منيو الجهاز**، أو حد عنده يفتحلك المنيو.
- **الـ Comm Key** لو متظبط (`Menu → Comm. → Connection → Comm Key`).
- بيسحبوا البصمات إزاي دلوقتي؟
  - برنامج على كمبيوتر (ZKTime.Net / ZKAccess) ← البرنامج ده بيقرأ من الجهاز.
  - BioTime / ZKBioSecurity ← الجهاز هو اللي بيبعت للبرنامج ده.
  - فلاشة USB ويحطوها في Excel.
  - **ده مهم** عشان تعرف ترجّع إيه في الآخر.
- موافقة إنك توصّل لابتوب على الشبكة، وكابل شبكة أو port فاضي جنب الجهاز.
- **نص ساعة** بعيد عن مواعيد الحضور، وموظف واحد يعمل بصمة كذا مرة.
- لو هما عملاء عندنا على السيستم: مين الموظفين المسجلين على الجهاز.

### 1.2 جهّز اللابتوب (في البيت، فيه نت)

**1) شغّل السميولشن كامل مرة واحدة**، عشان الـ images تتحمّل واللاب يشتغل بعد كده
من غير نت. في **Terminal 1** (`hr-platform`):

```bash
scripts/devices-lab.sh up
scripts/devices-lab.sh seed
scripts/devices-lab.sh simulate
```

- **المفروض تشوف** كل السطور فيها <span dir="ltr">`[PASS]`</span>. لو فيه `FAIL` ماتروحش بيه: افتح
  issue وحط فيه السطر، ويتصلّح الأول.
- الأمر `seed` بيطبع سطر زي:
  <span dir="ltr">`lab company 5 (شركة الفجر 5), branch 22 (فرع الجنوب 22)`</span> ← **اكتب اسم الشركة
  واسم الفرع**. فورم التخصيص في الداشبورد بيعرض الأسامي بس، مش الأرقام.

**2) اتأكد إن الأدوات شغالة من غير أي install.** في **Terminal 2** (افتحه وادخل
`devices-agent` مرة واحدة: `cd hr-platform/devices-agent`). المفروض يطبع `0.1.0`:

```bash
python3 -m workin_devices version
```

**3) افتح port الـ capture في الـ firewall** (في Terminal 2 برضه):

```bash
sudo ufw allow 8081/tcp
```

**4) قرر البصمات هتتسجل فين:**

| الوضع | يعني إيه | إمتى |
|---|---|---|
| **A — لوكال (الافتراضي)** | كل حاجة على اللابتوب، في داتابيز تجريبية | أول زيارة |
| **B — البرود** | البصمات تتسجل في داتابيز البرود | بس بعد ما شروط الخطوة 12 تخلص كلها |

**5) الشنطة:**

- شاحن اللابتوب
- USB-to-Ethernet adapter
- 2 كابل شبكة
- switch صغير
- فلاشة FAT32
- الموبايل للـ hotspot
- الدليل ده

---

## 2. أول ما توصل: صوّر الجهاز

قبل ما تلمس أي حاجة، صوّر:

| # | الشاشة | ليه |
|---|---|---|
| 1 | الستيكر اللي ورا الجهاز (Model، **SN** = السيريال، MAC) | السيريال هو اللي هنسجّل بيه الجهاز |
| 2 | `Menu → System Info → Device Info` (أو About) | الـ firmware ونسخة الـ Push/ADMS |
| 3 | `Menu → Comm. → Ethernet` | IP الجهاز والـ gateway |
| 4 | `Menu → Comm. → Cloud Server Setting` (أو ADMS) | **دي اللي هترجّعها في الآخر**. لو الشاشة مش موجودة ← الجهاز قديم |
| 5 | `Menu → System → Date Time` | الساعة، والـ time zone، و**التوقيت الصيفي (Daylight Saving / DLST) شغال ولا لأ**، والساعة بتتظبط لوحدها (NTP) ولا يدوي. **هتحتاجهم في الخطوة 5.3** |
| 6 | <span dir="ltr">`Menu → Data Mgt.`</span> (أو Record) | عدد سجلات الحضور وعدد الموظفين |

أجهزة **Hikvision** معظم الكلام ده بيبقى في صفحة الويب بتاعتها (الخطوة 8).

---

## 3. اتوصّل بالشبكة ودوّر على الأجهزة

في **Terminal 2**:

```bash
ip -4 addr
ping -c 3 192.168.1.201
python3 -m workin_devices scan --cidr 192.168.1.0/24
```

- أمر `ip -4 addr` بيطبع IP اللابتوب، مثلاً `192.168.1.57/24`.
- في أمر `ping` حط IP الجهاز من صورة رقم 3.
- أمر `scan` بيطبع سطر لكل جهاز لقاه، وبيحفظ النتيجة في `field-report/scan-*.json`.

**اقرأ النتيجة كده:**

| لو شفت في السطر | يبقى الجهاز | روح على |
|---|---|---|
| `zk_tcp` وجواه `serial` و `firmware` | ZKTeco بيرد على 4370 | الخطوة 4 |
| `zk_tcp` وجواه `comm_key_required: true` | ZKTeco عليه Comm Key | الخطوة 4 مع <span dir="ltr">`--comm-key`</span> |
| `zk_udp` | ZKTeco قديم بيرد على UDP بس | الخطوة 4 مع <span dir="ltr">`--udp`</span> |
| <span dir="ltr">`hikvision (ISAPI)`</span> | Hikvision | الخطوة 8 |
| <span dir="ltr">`dahua (CGI)`</span> أو port 37777 | Dahua | الخطوة 9 |

**مشاكل ممكن تقابلك:**

| المشكلة | الحل |
|---|---|
| `ping` مش بيرد | اتأكد إن اللابتوب على نفس الشبكة: الـ IP لازم يبدأ بنفس الأرقام (مثلاً <span dir="ltr">`192.168.1.x`</span>). لو الشبكة مافيهاش DHCP، اسأل عن IP فاضي وحطه يدوي |
| `scan` مالقاش الجهاز | استخدم **كابل مش WiFi** (شبكات WiFi كتير بتمنع الأجهزة تشوف بعض). اتأكد من IP الجهاز من صورة رقم 3 |
| `scan` قال إن الشبكة كبيرة | اسكان على جزء أصغر، مثلاً <span dir="ltr">`--cidr 192.168.1.0/24`</span> |

---

## 4. خد نسخة احتياطية من سجل جهاز ZKTeco (قبل أي تغيير)

الخطوة دي **قراءة بس**، ولازم تتعمل قبل ما تغيّر أي إعداد. بتاخد نسخة من كل
سجلات الحضور على اللابتوب، عشان لو حصل أي حاجة يبقى معانا نسخة.

في **Terminal 2**:

```bash
python3 -m workin_devices zk-info --host 192.168.1.201 \
  --backup field-report/SN-attlog-backup.tsv
```

(غيّر `SN` لسيريال الجهاز.)

**المفروض تشوف:** `serial` و `firmware` و `records` و `device_time` و
`laptop_time`، وبعدها <span dir="ltr">`backed up N record(s)`</span>.

**اتأكد من دول واكتبهم:**

- [ ] السيريال (`serial`) هو نفس اللي على الستيكر.
- [ ] `records` هو نفس العدد اللي على شاشة الجهاز.
- [ ] الفرق بين `device_time` و `laptop_time`: لو أكتر من كام دقيقة، اكتبه (ساعة الجهاز مش مظبوطة).
- [ ] آخر كام سطر في ملف الـ backup نفس اللي في شاشة البحث في الجهاز
      (`Menu → Attendance Search`) لموظف واحد: نفس الكود ونفس المواعيد.

**مشاكل:**

| لو ظهر | يعني | اعمل |
|---|---|---|
| `comm_key_required: true` | الجهاز عليه Comm Key | اسأل عليه وضيف <span dir="ltr">`--comm-key 12345`</span> |
| `comm_key_required: true` **تاني** بعد ما حطيت <span dir="ltr">`--comm-key`</span> | الـ Comm Key غلط (`zk-info` بيطبع نفس السطر للمفتاح الغلط) | اتأكد منه من المنيو |
| `did not answer in time` | الجهاز مش بيرد على TCP | جرّب تاني بـ <span dir="ltr">`--udp`</span>. لو برضه لأ، جرّب بعد شوية (ممكن الجهاز مشغول) |
| `cannot reach ... over TCP` | IP غلط أو الجهاز مش على الشبكة | ارجع للخطوة 3 |
| عدد `records` مختلف عن الشاشة | ممكن حد عمل بصمة دلوقتي | أعد الأمر. لو لسه مختلف، اكتبها ملاحظة |

---

## 5. جهاز ZKTeco بيعمل Push (عنده Cloud Server Setting)

**بس** لو في الخطوة 2 لقيت شاشة `Cloud Server Setting` أو `ADMS`. لو مش موجودة
← روح الخطوة 6.

الفكرة: هنخلي الجهاز يبعت البصمات للابتوب بدل ما يبعتها لمكانها العادي، وبرنامج
`capture` على اللابتوب يسجل من اللي الجهاز بيبعته بس اللي السيستم بيحتفظ بيه
(قاعدة 7)، ويبعته **كله** للسيستم بتاعنا.

### 5.1 شغّل السيستم والـ capture على اللابتوب

في **Terminal 1** (وضع A — لوكال):

```bash
scripts/devices-lab.sh up
```

في **Terminal 3** (افتحه وادخل `devices-agent`: `cd hr-platform/devices-agent`):

```bash
python3 -m workin_devices capture --listen 0.0.0.0:8081 \
  --upstream http://127.0.0.1:18080 --host-header devices.localhost \
  --out field-report/captures
```

(في وضع B نفس الأمر بس <span dir="ltr">`--upstream http://127.0.0.1:80`</span>، شوف الخطوة 12.)

**المفروض تشوف:** سطر زي
`on the terminal: Server Address = 192.168.1.57   Server Port = 8081`
← **ده اللي هتكتبه في الجهاز.**

**افتح الداشبورد في المتصفح:** `https://localhost:18443/admin/devices?live=1`

- هيطلعلك تحذير الشهادة (certificate) ← اضغط Advanced ثم Proceed.
- **قبل ما تملى أي فورم** (تخصيص جهاز، أو توكن agent): دوس **Stop live refresh**
  (إيقاف التحديث المباشر) الأول. الصفحة بتعمل refresh كل 10 ثواني وبتمسح اللي
  كتبته. رجّعه بعدها بـ **Live: refresh every 10 seconds** (مباشر: تحديث كل 10
  ثوانٍ).
- الباسورد: `devpassword`.
- الـ <span dir="ltr">`?live=1`</span> في آخر اللينك معناها إن الصفحة بتعمل refresh لوحدها كل 10 ثواني.
- الداشبورد بيفتح **بالعربي**. الدليل بيكتب أسامي الزراير بالإنجليزي، والجدول ده
  بيقولك هتلاقيها إزاي قدامك (أو اختار English تحت في السايدبار).

| في الدليل | في الداشبورد بالعربي |
|---|---|
| Attendance devices | أجهزة البصمة |
| Terminals waiting to be allocated | أجهزة في انتظار التخصيص |
| Allocate to a branch | تخصيص لفرع |
| Device time zone | المنطقة الزمنية للجهاز |
| Delivered via | طريقة الوصول |
| State / verification | الحالة / طريقة التحقق |
| On-premises agents | الوكلاء المحليون |
| Issue agent token | إصدار رمز وكيل |
| Import a USB export | استيراد ملف من فلاشة USB |
| Unreadable lines | أسطر غير مقروءة |
| Deactivate device | إيقاف الجهاز |
| Revoke (جنب الـ agent) | إنهاء |
| Restore (agent متلغي) | إعادة تفعيل |
| Activate device (جهاز متوقف) | تفعيل الجهاز |
| Active / Inactive (الحالة) | نشط / غير نشط |
| Stop live refresh | إيقاف التحديث المباشر |
| Live: refresh every 10 seconds | مباشر: تحديث كل 10 ثوانٍ |
| All companies | كل الشركات |
| <span dir="ltr">That serial number is already allocated.</span> | هذا الرقم التسلسلي مخصص بالفعل. |
| <span dir="ltr">The file is too large to upload here.</span> | الملف أكبر من المسموح هنا. |

### 5.2 خلّي الجهاز يبعت للابتوب

(صوّرت الشاشة دي في الخطوة 2؟ لو لأ، صوّرها دلوقتي.)

على الجهاز: `Cloud Server Setting`:

1. الـ Server Mode = **ADMS**.
2. **Enable Domain Name** = OFF.
3. **Server Address** = IP اللابتوب (من 5.1).
4. **Server Port** = `8081`.
5. **HTTPS** = OFF، **Proxy** = OFF.
6. Save. ولو طلب restart، اعمل restart.

**المفروض تشوف خلال دقيقة:**

- في Terminal 3 (الـ capture): سطر فيه <span dir="ltr">`NEW [SN] GET /iclock/cdata?...`</span> (مكان `SN` هتلاقي السيريال).
- في الداشبورد: السيريال ظهر في جدول **"Terminals waiting to be allocated"** (أجهزة في انتظار التخصيص).
- الجهاز لسه **مش متخصص** (مش مربوط بشركة)، وده مقصود: السيستم بيرفض يستلم منه
  بصمات (`403`)، فالجهاز بيحتفظ بكل حاجة عنده ومفيش بصمة بتضيع.
- **خد بالك:** السيستم بيرد عليه بإعدادات الإرسال العادية (`Delay=10`،
  `TransTimes=00:00;14:05`، `TransInterval=1`، `Realtime=1`، وغيرها)، **من غير
  الـ time zone**. في أجهزة بتحفظ الإعدادات دي، وعشان كده في الخطوة 10 لازم
  تتأكد إن برنامج العميل بيستلم بصمة جديدة بعد ما ترجّع الإعداد.
- في نفس الشاشة: اكتب **فيه اختيار `Enable Domain Name` ولا لأ** (سيبه OFF).

**لو مفيش حاجة ظهرت بعد دقيقة:**

| السبب المحتمل | اعمل |
|---|---|
| الـ firewall | `sudo ufw allow 8081/tcp` و `sudo ufw status` |
| IP اللابتوب اتغير | بص على `ip -4 addr` وصحّح الـ Server Address |
| الجهاز محتاج restart | اعمل restart للجهاز |
| الجهاز واللابتوب مش على نفس الشبكة | من موبايل على نفس الشبكة افتح `http://LAPTOP-IP:8081` (IP اللابتوب مكان `LAPTOP-IP`)؛ لو مافتحش يبقى الشبكة مانعة |
| Enable Domain Name شغالة أو Proxy شغال | اقفلهم |
| الـ capture بيطبع <span dir="ltr">`502 UPSTREAM ERROR`</span> | السيستم مش شغال ← وضع A: `scripts/devices-lab.sh up`؛ وضع B: الـ stack بتاع 12.2 (شوف 12.4). **الجهاز مش هيضيع حاجة**، هيعيد المحاولة |

### 5.3 خصّص الجهاز لفرع (ركّز هنا)

> **تحذير:** أول ما تخصّص الجهاز، السيستم بيبعتله الـ time zone في كل اتصال،
> وفيه أجهزة بتغيّر ساعتها على حسبه. **لازم تختار نفس الـ time zone اللي الجهاز
> متظبط عليه أصلاً** (صورة رقم 5 من الخطوة 2).

| الجهاز متظبط على | اكتب في Device time zone |
|---|---|
| <span dir="ltr">+02:00</span> ثابت | <span dir="ltr">`+02:00`</span> |
| <span dir="ltr">+03:00</span> ثابت | <span dir="ltr">`+03:00`</span> |
| بيتبع التوقيت الصيفي لمصر لوحده | `Africa/Cairo` |
| مش متأكد | **ماتخمّنش.** افتح `Date Time` تاني: لو Daylight Saving شغال اختار `Africa/Cairo`؛ لو مقفول اختار الـ offset اللي ساعة الجهاز ماشية عليه دلوقتي (قارنها بساعة اللابتوب) |

**في الداشبورد:** جدول "Terminals waiting" ← زرار **Allocate to a branch** جنب
السيريال:

- خانة **Serial number:** بيتملى لوحده. **اتأكد إنه نفس الستيكر.**
- **Vendor:** ZKTeco.
- **Device name:** أي اسم، مثلاً "بوابة الشركة".
- **Device time zone:** من الجدول اللي فوق.
- **Branch:**
  - وضع A: اختار الفرع **بالاسم** اللي `seed` طبعه (مثلاً "شركة الفجر 5 — فرع الجنوب 22").
  - وضع B: فرع الشركة الحقيقي.
- اضغط **Allocate to a branch**.

**المفروض تشوف:**

- صفحة الجهاز اتفتحت.
- في Terminal 3: بعد الاتصال الجاي، `POST /iclock/cdata?...table=ATTLOG`. ده الجهاز
  بيبعت **كل سجلاته القديمة** (عادي ياخد شوية لو السجل كبير).
- في الداشبورد: البصمات بتظهر في صفحة الجهاز.
- الـ <span dir="ltr">`TimeZone=`</span> **مش بيظهر على الشاشة** (الشاشة بتعرض أول 20 حرف من الرد بس).
  هتلاقيه إزاي: تحت.

**فين تلاقي <span dir="ltr">`TimeZone=`</span>:** في رد الـ handshake (`GET /iclock/cdata`)، **مش** في رد
`GET /iclock/getrequest` اللي بيتكرر كل 10 ثواني (ده ردّه `OK` بس). في Terminal 2
(غيّر `SN` للسيريال):

```bash
grep -l '"path": "/iclock/cdata' field-report/captures/SN/*-GET.json | xargs -r ls -t | head -n 1
```

- لو طبع `No such file or directory` (bash) أو `no matches found` (zsh): اسم
  الفولدر غلط. اكتب `SN` بالظبط زي ما هو في <span dir="ltr">`ls field-report/captures/`</span>.
- لو **مطبعش حاجة**: الفولدر صح بس مفيش handshake متسجل فيه لسه.
- لو طبع اسم ملف، ده آخر handshake بالوقت (مش بالرقم اللي في أول الاسم: الرقم ده
  بيبدأ من 1 كل مرة الـ capture يتشغّل)، مثلاً <span dir="ltr">`00042-20260916-081502-GET.json`</span>.
  افتح الملف اللي بنفس الاسم بس آخره <span dir="ltr">`-GET.response.bin`</span>: فيه سطر <span dir="ltr">`TimeZone=`</span>. لو
  الوقت في اسم الملف **قبل** ما خصّصت الجهاز، يبقى الجهاز لسه ماعملش handshake
  جديد: غالباً بيعمله لما يتصل من الأول (زي بعد تجربة 5، شيل الكابل). بص تاني
  بعدها، ولو برضه مفيش اكتبها.

| لو شفت | يعني | اعمل |
|---|---|---|
| البصمات **UNMATCHED** | كود الموظف على الجهاز مش مربوط بموظف عندنا | **في وضع A ده طبيعي** (الداتابيز تجريبية). في وضع B اكتب الأكواد، وهنربطها بعدين |
| مواعيد البصمات **غلط بساعة أو ساعتين** | الـ time zone في التخصيص مش زي الجهاز | ماتغيّرش حاجة في الجهاز. اكتبها في الـ issue (الخطوة 11)، ولو في وضع B بلّغ صاحب الريبو في نفس اليوم. في وضع A ممكن تمسح اللاب وتبدأ تاني بالخطوات التلاتة اللي في 6.4 ("لو عايز تعيد التجربة"): `down --wipe` لوحده مش كفاية، لأن `seed` بيعمل توكن جديد والـ agent فاكر إنه بعت |
| في الـ capture `413` بيتكرر لنفس الرفع | الجهاز بيبعت أكتر من 5000 سجل مرة واحدة | **معلومة مهمة**: اكتبها، وقف التجربة، ورجّع الجهاز (الخطوة 10) |
| `That serial number is already allocated` | السيريال متخصص قبل كده | افتحه من جدول Attendance devices |

### 5.4 التجارب مع الموظف (واكتب النتايج)

> **تجربة 5 و 6 بيأثروا على الجهاز الحقيقي:** اعملهم **بعيد عن مواعيد الحضور
> والانصراف، ومع الموظف اللي متفق معاه بس**. الجهاز بيحتفظ بالبصمات ومفيش حاجة
> بتضيع، بس ماينفعش حد تاني يبصم وإنت شايل الكابل.

| # | اعمل | المفروض تشوف | اكتب |
|---|---|---|---|
| 1 | موظف يعمل بصمة عادي | بصمة جديدة في الداشبورد **خلال ثواني**: الكود والوقت، و *Delivered via* = `PUSH` | وصلت خلال كام ثانية؟ |
| 2 | يعمل بصمة وهو دايس زرار **Check-Out** (أو F2) | عمود *State / verification*: الرقم الأول اتغير | رقم الدخول = ؟ رقم الخروج = ؟ الموظفين أصلاً بيدوسوا الزرار ده؟ |
| 3 | يعمل بصمتين ورا بعض في أقل من 10 ثواني | الاتنين اتسجلوا | اتسجلوا؟ |
| 4 | افتح ملف <span dir="ltr">`*.request.bin`</span> لأي `ATTLOG` في <span dir="ltr">`field-report/captures/SN/`</span> (SN هو السيريال) | (أ) الوقت مكتوب <span dir="ltr">`2026-09-16 08:01:02`</span> ولا رقم طويل (10 أرقام)؛ (ب) كل سطر فيه كام حقل، والفاصل Tab ولا حاجة تانية؛ (ج) كود الموظف (أول حقل) طوله كام رقم | شكل الوقت، عدد الحقول والفاصل، طول الكود. لو السطور مكتوبة <span dir="ltr">`# unparsed line shape:`</span> يبقى السيستم مش فاهمها: الشكل بيوريك الفاصل والأطوال (قاعدة 7)، **اكتبها** |
| 5 | **شيل كابل الشبكة من الجهاز**، الموظف يعمل بصمتين، رجّع الكابل | البصمتين يوصلوا بعد ما الكابل يرجع، **بمواعيدهم الأصلية** | وصلوا؟ بعد قد إيه؟ |
| 6 | في Terminal 3 اضغط **Ctrl+C**، الموظف يعمل بصمة، **استنى 10 دقايق**، شغّل أمر الـ capture تاني (سهم لفوق ثم Enter) | البصمة توصل بعد ما الـ capture يرجع، **بميعادها الأصلي، وتظهر مرة واحدة** في الداشبورد | الجهاز عاد الإرسال؟ بعد قد إيه؟ اتسجلت مرة واحدة؟ |
| 7 | افتح أي ملف <span dir="ltr">`*-GET.json`</span> الـ path بتاعه بيبدأ بـ <span dir="ltr">`/iclock/cdata`</span> (الـ handshake، مش `getrequest`) | في الـ path: <span dir="ltr">`pushver=`</span> و <span dir="ltr">`DeviceType=`</span> و <span dir="ltr">`language=`</span> و <span dir="ltr">`PushOptionsFlag=`</span> | القيم |
| 8 | افتح ملف <span dir="ltr">`*-POST.json`</span> لأي ATTLOG | الـ `Content-Type` جوه `request_headers`، و <span dir="ltr">`Stamp=`</span> في الـ path | القيمتين (الـ Stamp رقم عادي ولا شكل تاني؟) |
| 9 | بص على أكبر ملف `ATTLOG` | عدد السطور فيه، وهل فيه `413` | أكبر عدد سجلات في رفعة واحدة |
| 10 | منيو الجهاز | فيه اختيار HTTPS؟ | أيوه / لأ |
| 11 | قارن 3 بصمات في الداشبورد بشاشة البحث في الجهاز | نفس الكود ونفس الوقت | متطابقين؟ |

الجدول ده والصور بتاعة الخطوة 2 بيغطوا الـ checklist اللي في الخطوة 4 من
[zkteco-adms-receiver-setup.md](zkteco-adms-receiver-setup.md). 3 حاجات منه
**مش بيتختبروا في الزيارة دي**، اكتبهم "لم يُختبر": الـ TLS versions (الـ
capture شغال HTTP بس)، وهل `TimeZone` بيقبل دقايق (محتاج نغيّر في الجهاز)، و
"Acknowledgement dropped" (تجربة 6 بتوقف الاستقبال كله، مش بترفض رفعة بعد ما
الجهاز يبعتها).

---

## 6. جهاز ZKTeco قديم (4370): عن طريق الـ agent

للجهاز القديم اللي مالوش Cloud Server، **وكمان** لأي جهاز ZKTeco رد على 4370
(حتى لو بيعمل Push)، عشان نتأكد إن الطريقتين بيدّوا نفس النتيجة.

### 6.1 جهّز

**1) خصّص الجهاز الأول** (زي 5.3). لو جهاز قديم مابيظهرش في "Terminals waiting"
لوحده، اكتب السيريال بإيدك في فورم **Allocate to a branch** (نفس اللي طلع في
`zk-info`). وقّف التحديث المباشر الأول (5.1).

**2) التوكن:**

- **وضع A:** استخدم توكن اللاب اللي عمله `seed`. في Terminal 2:
  `mkdir -p field-report && cp lab/agent.token field-report/agent.token`
  (التوكن ده تبع شركة اللاب، ولازم الجهاز يكون متخصص **لفرع نفس الشركة**.)
- **وضع B:** من الداشبورد ← **On-premises agents** ← اختار الشركة ← اكتب اسم
  ← **Issue agent token** (بعد ما توقّف التحديث المباشر، 5.1) ← انسخ التوكن **(بيظهر
  مرة واحدة بس)**. التوكن ده باسورد للبرود، فماتكتبهوش جوه أمر (هيتحفظ في الـ
  history). اكتب الأمر اللي تحت في Terminal 2 والصق التوكن لما يطلب (مش هيظهر
  وإنت بتلصقه).

الأمر ده لوضع B بس:

```bash
if [ -d workin_devices ]; then
  mkdir -p field-report
  printf 'Agent token: '; read -rs TOKEN; echo
  (umask 077 && printf '%s\n' "$TOKEN" > field-report/agent.token)
  unset TOKEN; echo saved
else echo 'WRONG TERMINAL: use Terminal 2 (devices-agent)'; fi
```

**3)** في Terminal 2: `chmod 600 field-report/agent.token`

**4)** اعمل ملف `field-report/zk.toml`. **جهاز واحد بس في كل ملف:** `once` بيقرأ
ويبعت **كل** الأجهزة اللي في الملف. لجهاز ZKTeco تاني اعمل ملف تاني (مثلاً
`zk2.toml` بـ <span dir="ltr">`spool_path = "zk2.sqlite3"`</span>).

```toml
server_url = "https://localhost:18443"
token_file = "agent.token"
spool_path = "zk.sqlite3"
insecure_skip_tls_verify = true
in_out_field = "punch"

[[devices]]
serial = "SERIAL FROM STICKER"
kind = "zk"
host = "192.168.1.201"
comm_key = 0
udp = false
```

غيّر في الملف:

- **السيريال** (`serial`): اكتبه مكان `SERIAL FROM STICKER` زي الستيكر بالظبط. لو نسيته، `doctor`
  هيطلع خطأ `is not a serial the platform accepts` ومش هيقرأ حاجة.
- **الـ Comm Key** (`comm_key`): لو الجهاز عليه Comm Key اكتبه، غير كده سيبه `0`.
- **الـ UDP** (`udp`): خليه `true` لو `zk-info` اشتغل بـ <span dir="ltr">`--udp`</span> بس.
- **وضع B:** <span dir="ltr">`server_url = "https://localhost"`</span>.
- **الشهادة:** `insecure_skip_tls_verify = true` عشان الشهادة المحلية بتاعة اللابتوب
  بس.

> **الترتيب هنا مهم:** `doctor` بيقرأ بس ومابيبعتش حاجة، و `once` بيبعت **السجل
> كله**. كل بصمة بتتسجل بالـ in/out بتاعها، فلو بعتّ بـ `in_out_field` غلط
> وبعدين غيّرته، أول `once` بعدها هيبعت **السجل كله تاني** كبصمات جديدة. عشان
> كده الـ in/out بيتظبط في 6.2 و 6.3 **قبل** أي `once`.

### 6.2 اقرأ بس (doctor)

الأمر ده بيقرأ بس، ومابيبعتش حاجة. في Terminal 2:

```bash
python3 -m workin_devices doctor --config field-report/zk.toml
```

**المفروض تشوف:** `OK` والسيريال، وآخر 3 بصمات جنب كل واحدة <span dir="ltr">`in/out=`</span>.

### 6.3 الدخول والخروج (قبل ما تبعت أي حاجة)

الأمر بيقرأ العمودين من بصمتين: دخول وخروج. لو الجهاز سجّل الاتنين بنفس الكودين
<span dir="ltr">(`punch=5, status=1` في الحالتين، زي ADZV224371697)</span> فالبصمتين
مابيقولوش حاجة، والأمر ساعتها بيقرأ **توزيع الكودين في سجل الجهاز كله** — بس عشان
**يستبعد** عمود، مش عشان يختار واحد. عمود ثابت على قيمة واحدة في كل السجل تقريباً
مايقدرش يكون هو اللي بيفرّق بين دخول وخروج، لأن سجل بآلاف البصمات فيه الاتنين؛ ساعتها
العمود التاني هو الباقي، بشرط إنه هو نفسه متوازن. مثال من جهاز حقيقي:
<span dir="ltr">`punch`</span> اتقسم 5779 / 5640، و<span dir="ltr">`status`</span> كان 1
في 11424 بصمة و15 في بصمتين بس — فـ <span dir="ltr">`status`</span> اتستبعد و
<span dir="ltr">`punch`</span> فضل.

**ليه مش بنختار المتوازن على طول:** لو الموظفين نادراً بيدوسوا زرار الخروج، عمود
الدخول/الخروج نفسه بيبقى مايل، ولو الجهاز بيتستخدم ببصمة وكارت مثلاً بيبقى
<span dir="ltr">`status`</span> هو المتوازن — واختياره ساعتها يبعت السجل كله بعمود غلط.
في الحالة دي الأمر بيقول "مش واضح" ويسيبها ليك. النتيجة والتوزيع الاتنين بيتكتبوا في ورقة
النتائج؛ لو مش واضح، اكتب التوزيع في الـ issue: الأرقام دي هي الدليل، مش تخمين.

1. الموظف يعمل بصمة **Check-Out** (زي تجربة 2 في 5.4).
2. شغّل `doctor` تاني ← بص على آخر سطر <span dir="ltr">`in/out=`</span>.
3. **لو بصمة الخروج `in/out=1`** ← تمام، سيب <span dir="ltr">`in_out_field = "punch"`</span>.
4. **لو مش `1`** ← غيّرها لـ <span dir="ltr">`in_out_field = "status"`</span> وشغّل `doctor` تاني.
   **اكتب النتيجة.**
5. **لو الجهاز بيعمل Push كمان (عملت الخطوة 5):**
   - نفس بصمة الخروج في الداشبورد: الرقم الأول في *State / verification* لازم
     يساوي <span dir="ltr">`in/out=`</span> في `doctor`. لو مش بيساويه ← القيمة التانية لـ
     `in_out_field` هي الصح، جرّبها بـ `doctor`.
   - لو تجربة 4 في 5.4 طلعت إن الوقت **رقم طويل**: البصمة اللي جاية بالـ Push
     واللي جاية بالـ agent هيتسجلوا **مرتين** مهما عملت. في وضع B **ماتشغّلش
     `once` على الجهاز ده**، واكتبها. في وضع A عادي.

### 6.4 ابعت (once)

الأمر ده بيقرأ **ويبعت**. شغّله مرتين ورا بعض في Terminal 2:

```bash
python3 -m workin_devices once --config field-report/zk.toml
python3 -m workin_devices once --config field-report/zk.toml
```

**المفروض تشوف:**

- أول `once`:
  - جهاز قديم (من غير Push): <span dir="ltr">`stored=`</span> رقم كبير (كل السجل).
  - جهاز بيعمل Push: `stored=0` أو رقم صغير، لأن البصمات دي وصلت بالـ Push قبل
    كده. وكل بصمة تظهر **مرة واحدة** في الداشبورد (Delivered via = `PUSH`).
- تاني `once`: `stored=0` ← صح، مفيش حاجة جديدة.
- في الداشبورد: بصمات الجهاز القديم بـ *Delivered via* = `AGENT`.

**لو كل بصمة ظهرت مرتين** بعد `once`:

- ماتغيّرش `in_out_field` وتبعت تاني: ده هيعمل **نسخة تالتة**.
- اكتب: شكل الوقت (تجربة 4)، و <span dir="ltr">`in/out=`</span> في `doctor` قدام الرقم الأول في الداشبورد.
- **وضع A:** لو عايز تعيد التجربة:
  1. في Terminal 1: `scripts/devices-lab.sh down --wipe` ثم `up` ثم `seed` (`seed`
     بيعمل توكن جديد وبيلغي القديم).
  2. في Terminal 2: امسح **كل** ملفات الـ spool (الـ agent فاكر إنه بعت، واللاب
     اتمسح كله):
     `rm -f field-report/zk.sqlite3 field-report/zk2.sqlite3 field-report/hik.sqlite3 field-report/usb.sqlite3` و
     `cp lab/agent.token field-report/agent.token` (التوكن الجديد).
  3. خصّص الجهاز تاني.
- **وضع B:** **ماتشغّلش `once` تاني** على الجهاز ده. البصمات المكررة بتفضل في
  `device_punches` (مش بتأثر على الحضور دلوقتي، لأن مفيش حاجة بتحوّل البصمات
  لحضور). اكتبها في الـ issue، وصاحب الريبو هو اللي يقرر تنضيفها.

**مشاكل الـ agent:**

| لو ظهر | يعني | اعمل |
|---|---|---|
| `config error: ... chmod 600` | ملف التوكن مفتوح للكل | (Terminal 2) `chmod 600 field-report/agent.token` |
| `config error: ... is plain http` | الـ `server_url` مكتوب `http` | خليه `https://localhost:18443` |
| `config error: ... is not a serial the platform accepts` | السيريال فيه مسافة أو حرف غريب | اكتبه زي الستيكر بالظبط |
| `SERIAL MISMATCH` / `configured as X but the terminal reports Y` | الـ IP ده لجهاز تاني | صحّح `serial` أو `host`. **مش هيبعت حاجة لحد ما يتطابقوا** |
| `not registered` | الجهاز مش متخصص، أو متخصص لشركة غير شركة التوكن | خصّصه لفرع في **نفس شركة التوكن** |
| `unauthorized` | التوكن غلط أو اتلغى | وضع A (Terminal 2): `cp lab/agent.token field-report/agent.token` (كل `seed` بيعمل توكن جديد). وضع B: اعمل توكن جديد (6.1 رقم 2) |
| `did not answer in time` | الجهاز مش بيرد | جرّب `udp = true`، واتأكد من الـ IP |
| `refused the communication key` | الـ Comm Key غلط | صحّح `comm_key` |
| `terminal clock is +N seconds` | ساعة الجهاز مش مظبوطة | اكتبها ملاحظة بس. **ماتغيّرش الساعة** |
| `retry: server answered 503` أو `unreachable` | السيستم واقف | شغّله. السجلات محفوظة ومش هتضيع |
| <span dir="ltr">`uid:`</span> في الكود بدل رقم | جهاز قديم جداً (نوع سجل 8 bytes) | معلومة مهمة، اكتبها. البصمات دي مش هتتقرأ دلوقتي |

---

## 7. ملف الـ USB

لكل جهاز ZKTeco، حتى اللي مش على الشبكة.

**1) وضع B، لو الجهاز ده اتقرأ قبل كده (خطوة 5 أو 6):** قبل ما تسحب الملف،
الموظف يعمل **بصمة دخول عادية وبعدها بصمة Check-Out**، واستنى لحد ما يوصلوا
(في الداشبورد بالـ Push، أو في `doctor`). هتحتاجهم في رقم 5.

**2)** على الجهاز: `Menu → USB Manager → Download → Attendance Data` على الفلاشة.
هيعمل ملف اسمه <span dir="ltr">`1_attlog.dat`</span> أو `attlog.dat` (أو اسم قريب). **اكتب اسمه
بالظبط**: الأوامر تحت مكتوب فيها <span dir="ltr">`1_attlog.dat`</span>، غيّره للاسم الحقيقي فيهم كلهم.

**3)** انسخه على اللابتوب في <span dir="ltr">`devices-agent/field-report/`</span>، **وسيب الملف الأصلي على
الفلاشة زي ما هو.**

**4) ملف الإعداد:** الرفع محتاج `server_url` والتوكن بس، فأي ملف من 6.1 أو 8 ينفع
(`zk.toml` أو `hik.toml`). لو معندكش ولا واحد: اعمل 6.1 رقم 2 و 3 (التوكن)،
واعمل `field-report/usb.toml` فيه **أول 4 سطور بس** من ملف 6.1 رقم 4، **وبدّل**
سطر `spool_path` فيهم بـ <span dir="ltr">`spool_path = "usb.sqlite3"`</span> (ماتضيفش سطر تاني بنفس
الاسم: الملف مش هيتقرأ).

**5) وضع B، لو الجهاز ده اتقرأ قبل كده (خطوة 5 أو 6):** الرفع بيسجل كل سطر
مالوش نسخة. لو ترتيب الأعمدة مختلف، أو الوقت متسجل بشكل تاني، **السجل كله
هيتسجل تاني** على البرود. فقبل ما ترفع:

- لو تجربة 4 طلعت إن الوقت **رقم طويل**: **ماترفعش في وضع B**. البصمات اللي
  وصلت بالـ Push متسجلة بالرقم الطويل، والملف بالتاريخ والوقت، فكل سطر هيتسجل
  تاني مهما كان (نفس سبب 6.3 رقم 5).
- غير كده، بص على آخر سطور الملف في Terminal 2:

```bash
tail -n 20 field-report/1_attlog.dat
```

لازم تلاقي في الملف **البصمتين** بتوع رقم 1 (نفس الكود ونفس الوقت): واحدة
دخول وواحدة خروج. لكل واحدة منهم، **الرقم اللي بعد الوقت** في الملف لازم
يساوي الرقم اللي بعد الوقت في ملف <span dir="ltr">`*.request.bin`</span> بتاع `ATTLOG` (تجربة 4)،
أو <span dir="ltr">`in/out=`</span> بتاعها في `doctor`. بصمة واحدة مش كفاية: عمود ثابت (مثلاً رقم
الجهاز `1`) ممكن يطابق بصمة الخروج بالصدفة.

لو واحدة من الاتنين مش بتساوي، أو مالقيتهمش: **ماترفعش في وضع B**. اكتبها
وحط السطور في الـ issue بعد ما تغيّر أكواد الموظفين.

**6) ارفعه** (لازم الجهاز يكون متخصص)، من **Terminal 2**:

```bash
python3 -m workin_devices import-usb --config field-report/zk.toml \
  --serial SN --file field-report/1_attlog.dat
```

(غيّر `SN` لسيريال الجهاز، و `zk.toml` لملفك، و <span dir="ltr">`1_attlog.dat`</span> لاسم الملف الحقيقي. ولو الملف أقل من 1 ميجا ممكن ترفعه من الداشبورد:
صفحة الجهاز ← **Import a USB export**.)

**هيطبع:** <span dir="ltr">`{"lines": ..., "stored": ..., "duplicates": ..., "unmatched": ..., "malformed": ...}`</span>

| النتيجة | يعني |
|---|---|
| الجهاز اتقرأ قبل كده (خطوة 5 أو 6) و `stored` قليل جداً و `duplicates` كبير | **تمام** ← ترتيب أعمدة الملف زي الـ Push |
| الجهاز اتقرأ قبل كده بس `stored` كبير (قريب من `lines`) | **ترتيب الأعمدة مختلف** (وده ماكانش المفروض يحصل في وضع B لو عملت رقم 5) ← اكتبها، وحط أول 3 سطور من الملف في الـ issue (الخطوة 11) **بعد ما تغيّر أكواد الموظفين** |
| `malformed` أكبر من صفر | سطور مش مفهومة ← هتلاقيها في صفحة الجهاز تحت *Unreadable lines* |
| `import failed: ... is not an active device` | الجهاز مش متخصص لشركة التوكن ← خصّصه |
| `No such file or directory` أو `FileNotFoundError` | اسم الملف في الأمر مش زي الملف الحقيقي ← <span dir="ltr">`ls field-report/`</span> وصحّح الاسم في `tail` و `import-usb` |
| من الداشبورد: <span dir="ltr">`الملف أكبر من المسموح هنا`</span> (The file is too large) | الملف أكبر من 1 ميجا ← استخدم أمر `import-usb` |

---

## 8. أجهزة Hikvision

**1)** افتح صفحة الجهاز في المتصفح: `http://DEVICE-IP` (حط IP الجهاز مكان
`DEVICE-IP`). محتاج **user و password الـ admin**، اسألهم عليهم.

**2)** قبل ما تغيّر حاجة صوّر:
`Configuration → Network → Advanced → HTTP Listening`.

**3)** احفظ الباسورد في ملف واقرأ الجهاز، من **Terminal 2**. **ماتكتبش الباسورد جوه
أمر** (هيتحفظ في الـ history): الأمر هيسألك عليه، اكتبه ودوس Enter (مش هيظهر
وإنت بتكتبه):

```bash
if [ -d workin_devices ]; then
  mkdir -p field-report
  printf 'Hikvision password: '; read -rs HIK_PW; echo
  (umask 077 && printf '%s' "$HIK_PW" > field-report/hik.pw)
  unset HIK_PW; echo saved
else echo 'WRONG TERMINAL: use Terminal 2 (devices-agent)'; fi
python3 -m workin_devices hik-info --host 192.168.1.64 --username admin \
  --password-file field-report/hik.pw --days 7 --dump field-report/hik-events.json
```

**هتشوف:** `serial` و `model`، و `events_with_employee_by_minor`: أكواد الأحداث
اللي فيها موظف، وعدد كل واحد. ملف `hik-events.json` فيه شكل كل حدث بس (قاعدة 7):
`major` و `minor` والوقت و `attendanceStatus`، من غير اسم الموظف ولا كوده ولا الكارت
ولا الصورة.

- إحنا بنعتبر الحضور الأكواد: `1` (كارت)، `38` (بصمة صباع)، `75` (وش).
- **قارن:** اعمل بصمة دلوقتي، ودوّر عليها في صفحة الأحداث بتاعة الجهاز: كودها كام؟
  لو كود غير 1 و38 و75 ← **اكتبه** (هنضيفه في `attendance_minors`).

**4)** خصّص الجهاز في الداشبورد (وقّف التحديث المباشر الأول، 5.1): **Vendor = Hikvision**، والسيريال اللي طلع من `hik-info`.

**5)** اعمل ملف **لوحده** `field-report/hik.toml` (مش في `zk.toml`: `once` بيبعت كل
الأجهزة اللي في الملف). لو لسه ماعملتش التوكن، اعمل 6.1 رقم 2 و 3 الأول:

```toml
server_url = "https://localhost:18443"
token_file = "agent.token"
spool_path = "hik.sqlite3"
insecure_skip_tls_verify = true

[[devices]]
serial = "SERIAL FROM HIK-INFO"
kind = "hikvision"
host = "192.168.1.64"
username = "admin"
password_file = "hik.pw"
```

غيّر في الملف:

- **السيريال** (`serial`): اكتبه مكان `SERIAL FROM HIK-INFO`، زي ما طلع من `hik-info`. لو
  نسيته، `doctor` هيطلع خطأ `is not a serial the platform accepts` ومش هيقرأ حاجة.
- **وضع B:** <span dir="ltr">`server_url = "https://localhost"`</span>.
- **الشهادة:** `insecure_skip_tls_verify = true` عشان الشهادة المحلية بتاعة اللابتوب
  بس.

**6)** شغّل `doctor` وبعده `once` زي 6.2 و 6.4، بس بـ <span dir="ltr">`--config field-report/hik.toml`</span>.

**7) (اختياري)** سجّل اللي الجهاز بيبعته لوحده:

- في `HTTP Listening` حط IP اللابتوب، port `8081`، URL <span dir="ltr">`/hik/SN`</span> (السيريال مكان `SN`).
- في Terminal 3: وقّف الـ capture اللي شغال (Ctrl+C) الأول، لأن الاتنين على
  port `8081`. وبعدين شغّل الأمر ده، من **غير** <span dir="ltr">`--upstream`</span>:

```bash
python3 -m workin_devices capture --listen 0.0.0.0:8081 --out field-report/captures
```

- لما تخلص: Ctrl+C، ولو لسه محتاج جهاز الـ Push رجّع أمر 5.1.
- الـ capture هيرد على الجهاز بـ `503`، فالجهاز هيحتفظ بالأحداث ومش هتضيع على
  العميل. إحنا بس بنسجّل شكل البيانات: أسامي الحقول وأكواد الأحداث ودخول/خروج، من
  غير أسامي الموظفين ولا أكوادهم ولا الكروت ولا الصور (قاعدة 7).
- **رجّع الإعداد في الآخر.**

| لو ظهر | اعمل |
|---|---|
| `answered 401` | الـ user أو الباسورد غلط |
| `failed: ... timed out` | جرّب <span dir="ltr">`--https`</span> لو الصفحة بتفتح بـ https |
| البصمات `malformed` | كود الموظف فيه حروف. اكتب شكله (السيستم بيقبل أرقام بس دلوقتي) |

---

## 9. أي نوع جهاز تاني (Dahua و Suprema و Anviz وغيرهم)

- صوّر شاشات الخطوة 2، واحتفظ بنتيجة `scan`.
- لو الجهاز فيه إعداد "server" أو "cloud": في Terminal 3 شغّل الـ `capture` من
  غير <span dir="ltr">`--upstream`</span> (نفس أمر 8 رقم 7، وقّف أي capture شغال الأول) ووجّهه عليه.
  الـ capture هيكتب شكل الـ JSON أو XML بس (قاعدة 7)؛ لو الجهاز بيبعت شكل تاني،
  هتلاقي في ملف الـ <span dir="ltr">`.json`</span> الـ path والـ headers ونوع الـ body وحجمه، وده كفاية
  نعرف منه الجهاز بيتكلم إزاي.
- **مفيش دعم لسه**، بس المعلومات دي هي اللي هتخلينا نعمل دعم.

---

## 10. قبل ما تمشي: رجّع كل حاجة

- [ ] **كل جهاز:** رجّع `Cloud Server Setting` زي الصورة بالظبط، أو اقفله لو كان
      مقفول. وفي Hikvision رجّع `HTTP Listening`.
- [ ] **لو العميل بيستخدم أي برنامج** (بيقرأ من الجهاز زي ZKTime.Net، أو الجهاز
      بيبعتله زي BioTime): موظف يعمل بصمة جديدة، وخليهم يتأكدوا إنها وصلت
      للبرنامج بتاعهم. ده كمان بيغطي إعدادات الإرسال اللي السيستم بعتها (5.2).
- [ ] وقّف الـ capture (Ctrl+C في Terminal 3).
- [ ] **وضع B بس:** في الداشبورد ← **On-premises agents** ← الـ agent اللي عملته ←
      **Revoke** (إنهاء) **مرة واحدة**. الصح: الحالة بقت **Inactive** (غير نشط)،
      والزرار بقى **Restore** (إعادة تفعيل). **ماتدوسش عليه تاني**: كده بترجّعه.
- [ ] امسح الباسوردات والتوكن من اللابتوب بالأمر اللي تحت، في **Terminal 2** (لازم
      يطبع `deleted`).

```bash
[ -d workin_devices ] && rm -f field-report/hik.pw field-report/agent.token && echo deleted || echo 'WRONG TERMINAL: use Terminal 2 (devices-agent)'
```

- [ ] اقفل port الـ capture: `sudo ufw delete allow 8081/tcp`
- [ ] شيل أي كابل أو switch إنت اللي حطيته.
- [ ] **وضع B بس:** في الداشبورد افتح كل جهاز خصّصته ← **Deactivate device** (إيقاف
      الجهاز) **مرة واحدة** (بصماته بتفضل محفوظة، وبيبطل يستقبل جديد). الصح: الحالة
      بقت **Inactive** (غير نشط)، والزرار بقى **Activate device** (تفعيل الجهاز).
      **ماتدوسش عليه تاني**: كده بترجّع الجهاز شغال على البرود. وبعدها الخطوة 12.5، وفيها مسح
      نسخ <span dir="ltr">`.env.remote-db`</span> لو اللابتوب مش جهاز صاحب الريبو.

**مشكلة:** العميل قال البرنامج بتاعهم مش بيستلم بعد ما رجّعت الإعدادات
← قارن الإعدادات بالصورة تاني حرف حرف، واعمل restart للجهاز. الجهاز محتفظ
بسجلاته، فبمجرد ما الإعداد يرجع صح البرنامج بتاعهم هيسحبها.

---

## 11. بعد الزيارة

1. انسخ فولدر <span dir="ltr">`devices-agent/field-report/`</span> في مكان خاص. **مش في الريبو.**
2. **افتح issue لكل جهاز** بالقالب
   <span dir="ltr">`.github/ISSUE_TEMPLATE/device-compatibility-finding.yml`</span>، وحط فيه **ورقة
   النتائج** (آخر الدليل) وأي مشكلة (FAIL، `413`، دخول/خروج مقلوب، ترتيب أعمدة
   USB، كود Hikvision، بصمات مكررة). **من غير أكواد موظفين أو أسامي أو أرقام كروت.**
3. الإجابات تتنقل في PR للملفين دول، وده اللي بيحوّل معلومات الـ documentation
   لمعلومات متأكدين منها من جهاز حقيقي:
   - جدول الموديلات والـ firmware: [attendance-device-model-and-firmware-inventory.md](attendance-device-model-and-firmware-inventory.md)
   - جدول إمكانيات كل ماركة: [vendor-capability-matrix.md](vendor-capability-matrix.md)

---

## 12. وضع B: البصمات على داتابيز البرود

### 12.1 لازم يكون خلص قبل ما تروح (بالترتيب)

**1) اللابتوب على `main` بعد دمج الكود ده**، ومفيش تعديلات محلية (الـ <span dir="ltr">`--build`</span>
بيبني من الفولدر زي ما هو). في Terminal 1:

```bash
git switch main && git pull --ff-only
git status --short
```

أمر `git status --short` لازم مايطبعش أي حاجة.

**2) جداول الأجهزة اتعملت على البرود (15 جدول).**

- ده تغيير في داتابيز البرود: **صاحب الريبو (repository owner) بس** اللي
  يشغّله أو يوافق عليه صراحة. أي agent session (AI) **ممنوع** تشغّله؛ مسموحلها
  تقرأ بس، ولما يتطلب منها.
- الخطوات في [provisioning-phase1-tables.md](../operations/provisioning-phase1-tables.md).
- أول حاجة شغّل `verify_phase1_tables.sql` (قراءة بس). بيطبع 7 أنواع سطور
  (`check_name`)، واقرأهم **كلهم**: (أ) و (ب) تحت.

**(أ) سطر `phase1 tables present`:** الـ verdict بيبدأ بإيه؟ الجدول ده بيقول **صاحب
الريبو** يعمل إيه. لو إنت مش صاحب الريبو: ماتشغّلش حاجة منه، ابعتله الـ verdict بس،
ووضع B مستني لحد ما يطلع `applied`.

| الـ verdict بيبدأ بـ | صاحب الريبو يعمل |
|---|---|
| `not applied -- apply it` | الخطوات كاملة في الـ provisioning runbook (فيها backup الأول) |
| `PRE-AGENTS -- apply upgrade_device_agents_and_delivery.sql (not --force), then re-run this script` | يشغّل ملف الـ upgrade ده بس، **من غير <span dir="ltr">`--force`</span>**، وبعدين الـ verify تاني |
| `applied -- if any table was here before this provisioning, compare definitions` | يكمّل لـ (ب). ولو فيه جدول من الـ 15 كان موجود قبل الـ provisioning، قارن تعريفاته زي الخطوة 1 في الـ provisioning runbook |
| أي حاجة تانية (`PARTIAL`، `PRE-DEVICE-TABLES`، ...) | **وقّف**. يعمل اللي مكتوب في الـ verdict نفسه، ووضع B مستني لحد ما يطلع `applied` |

**(ب) كل السطور التانية:** الـ verdict لازم يبدأ بـ `ok`، ما عدا سطر
`legacy tables` (ده معلومة بس). وبعد أي تصليح، الـ verify يتشغّل تاني من الأول.

| `check_name` | الصح | لو غير كده |
|---|---|---|
| `server`، `database` | `ok` | **وقّف** (`CHECK MANUALLY` أو `NOT InnoDB`) |
| `column counts` (سطر لكل جدول) | <span dir="ltr">`ok (count only)`</span> | **وقّف**. مثلاً `device_punches=24` مع `PRE-AGENTS` معناها الـ upgrade ماكملش، وكل رفع بصمات هيفشل |
| `phase1 collation`، `phase1 column collation` | `ok` | **وقّف** (`WRONG -- CONVERT TO utf8mb4_unicode_ci`): الأسامي والأعداد صح بس مقارنة كود البصمة بكود الموظف هتفشل وإنت في الشركة. صاحب الريبو يصلّحه بالخطوة 4b في الـ provisioning runbook |

**3) ملف `deploy/.env.remote-db`:** المفاتيح دي **موجودة أصلاً** في الملف (جاية من
`env.remote-db.example` بـ `false` و `devices.example.com`). **غيّر قيمها**،
ماتضيفش سطور جديدة:

```bash
DEVICES_INGEST_ENABLED=true
DEVICES_AGENTS_ENABLED=true
APP_DEVICES_DOMAIN=devices.localhost
ADMIN_ACTIONS_ENABLED=true
```

(المفتاح `ADMIN_ACTIONS_ENABLED=true` هو اللي بيظهر زراير التخصيص والتوكن والإيقاف في
الداشبورد. **اكتب قيمته القديمة** عشان ترجّعها في 12.5.)

واتأكد إن كل مفتاح موجود **مرة واحدة** (الأمر ده بيطبع عدد بس، مش القيم). في
**Terminal 1** (`hr-platform`):

```bash
for k in DEVICES_INGEST_ENABLED DEVICES_AGENTS_ENABLED APP_DEVICES_DOMAIN ADMIN_ACTIONS_ENABLED; do
  printf '%s ' "$k"; grep -c "^$k=" deploy/.env.remote-db
done
```

- لو طبع `1` ← تمام.
- لو طبع `0` ← الملف أقدم من المفتاح ده (مفاتيح الأجهزة اتضافت <span dir="ltr">2026-09-16</span>): **ضيف** السطر.
- لو طبع `2` أو أكتر ← امسح الزيادة وسيب سطر واحد.

**4) ملف `deploy/.env.remote-db` هو ملف صاحب الريبو نفسه**، اللي شغّل بيه Java على
البرود آخر مرة. الباسوردات والأسرار اللي فيه (<span dir="ltr">`DB_*`</span> و `JWT_SECRET` و
`ADMIN_PASSWORD`) **زي ما هي من غير تغيير**؛ التغيير الوحيد المسموح هو مفاتيح
رقم 3. **ماتعملش واحد جديد من الـ example.** السبب:

- البرود لسه شغال بـ PHP، فمفيش سيرفر عليه الملف ده. الـ Java بيشتغل على
  البرود من جهاز صاحب الريبو بس ([checking-against-the-live-database.md](../operations/checking-against-the-live-database.md)).
- الـ Java أول ما يقوم بيقارن `ADMIN_PASSWORD` بالـ hash اللي في `platform_admins`
  على البرود، وده اللي آخر تشغيل Java حطه (مش باسورد لوحة PHP). لو مختلف،
  **بيغيّره** للقيمة اللي في اللابتوب و**بيطلّع كل اللي عاملين login على داشبورد
  Java**؛ ولو الصف مش موجود بيعمله.

لو الملف اللي على اللابتوب نسخة منقولة، قارنه بملف صاحب الريبو **على نفس
الجهاز**، من غير ما يتطبع الباسورد أو أي hash ليه (Terminal 1، غيّر المسار
التاني لمكان ملف صاحب الريبو):

```bash
L=deploy/.env.remote-db; O=/PATH/TO/OWNER/.env.remote-db
if [ "$(grep -c '^ADMIN_PASSWORD=' "$L")" = 1 ] && [ "$(grep -c '^ADMIN_PASSWORD=' "$O")" = 1 ] \
  && cmp -s <(grep '^ADMIN_PASSWORD=' "$L") <(grep '^ADMIN_PASSWORD=' "$O")
then echo same; else echo 'DIFFERENT or unreadable'; fi
```

لازم يطبع `same`. لو طبع `DIFFERENT or unreadable` (باسورد مختلف، أو ملف مش
موجود، أو المسار لسه <span dir="ltr">`/PATH/TO/OWNER`</span>)، أو مش متأكد إن ده الملف اللي اتشغّل
بيه آخر مرة: **ماتشغّلش وضع B**.

**5) شركة العميل وفرعها موجودين على البرود.** لو مش عملاء عندنا ← استخدم وضع A.

### 12.2 تشغيل السيستم في الشركة

في **Terminal 1** (`hr-platform`):

```bash
(cd deploy && docker compose -f compose.remote-db.yaml -f compose.tls.yaml \
  -f compose.field-loopback.yaml --env-file .env.remote-db up -d --build)
```

> **الملف `compose.field-loopback.yaml` إجباري في الشركة.** من غيره، أي حد على شبكة
> العميل يقدر يفتح شاشة دخول البرود من اللابتوب، و`ufw` مش هيحميك لأن Docker
> بيعدّي من الـ firewall. معاه، اللابتوب بس اللي يوصل للسيستم، والجهاز بيوصل عن
> طريق الـ `capture` اللي بيعدّي <span dir="ltr">`/iclock`</span> بس.

- محتاج **نت على اللابتوب** (hotspot من الموبايل)، والكابل على شبكة العميل.
- في الـ stack ده الـ admin actions شغالة، **فماحدش يستخدم لوحة PHP القديمة في
  نفس الوقت.**
- **الفرق عن وضع A في الأوامر** في الجدول ده:

| الأمر | وضع B |
|---|---|
| الـ `capture` | <span dir="ltr">`--upstream http://127.0.0.1:80`</span> |
| الـ `server_url` في `zk.toml` و `hik.toml` و `usb.toml` | <span dir="ltr">`"https://localhost"`</span> |
| الداشبورد | `https://localhost/admin/devices?live=1` (باسورد البرود) |

### 12.3 إيه اللي بيتكتب على البرود

- **أول ما السيستم يقوم:** صف الأدمن في `platform_admins`: بيتعمل لو مش موجود،
  وبيتغيّر لو `ADMIN_PASSWORD` مختلف عن آخر تشغيل Java على البرود (12.1 رقم 4)،
  ومعاه إنهاء كل sessions داشبورد Java.
- **وهو شغال:** الـ login بتاعك (sessions ومحاولات الدخول)، ومسح دوري لمحاولات
  الدخول القديمة (نفس اللي أي تشغيل Java على البرود بيعمله).
- **من التجربة:**
  - الجهاز وتاريخ تخصيصه
  - الأجهزة اللي في الانتظار
  - البصمات الخام (`device_punches`) والسطور غير المفهومة
  - الـ agent
  - سجل الأدمن (audit)
- **مش بيتكتب:** **حضور.** مفيش حاجة بتحوّل البصمة لحضور دلوقتي، فالمرتبات وصفحات
  الحضور مش هتتأثر.

### 12.4 مشاكل وضع B

| المشكلة | اعمل |
|---|---|
| السيستم مش بيقوم (unhealthy) | غالباً مفيش نت أو الداتابيز مش بترد ← اتأكد من الـ hotspot، وشوف الـ logs بنفس أمر 12.2 بس بدّل `up -d --build` بـ `logs app` |
| Phase 1 schema check بيقول جداول أو أعمدة ناقصة | الخطوة 12.1 رقم 2 ماخلصتش ← **وقّف**، ماتكملش |
| الـ capture بيقول `502` | السيستم مش شغال ← شوف أول سطر |
| الشركة مش موجودة في قايمة الفروع | ارجع لوضع A |
| مفيش زرار Allocate أو Issue agent token، وفيه رسالة "إجراءات الإدارة معطّلة في هذا النشر" | `ADMIN_ACTIONS_ENABLED` مش `true` ← 12.1 رقم 3، وبعدها أمر 12.2 تاني |

### 12.5 بعد الزيارة

بالترتيب ده، **كله**:

**1) والسيستم لسه شغال:** اتأكد في الداشبورد إن الأجهزة اللي خصّصتها والـ agent
حالتهم **Inactive** (غير نشط)، والزراير بقت **Activate device** (تفعيل الجهاز)
و **Restore** (إعادة تفعيل). **ماتدوسش عليهم** (الخطوة 10).

**2) وقّف الـ stack** (Terminal 1). تعديل الملف لوحده مابيأثرش على السيستم وهو
شغال: الـ receiver والـ agent endpoint والـ admin actions بيفضلوا شغالين على
البرود لحد ما يقف.

```bash
(cd deploy && docker compose -f compose.remote-db.yaml -f compose.tls.yaml \
  -f compose.field-loopback.yaml --env-file .env.remote-db down)
```

**3) رجّع** `DEVICES_INGEST_ENABLED=false` و `DEVICES_AGENTS_ENABLED=false` في
`deploy/.env.remote-db`، عشان التشغيل الجاي على البرود (من غير
`compose.field-loopback.yaml`) مايفتحش الـ receiver. ورجّع `ADMIN_ACTIONS_ENABLED`
للقيمة القديمة اللي كتبتها في 12.1 رقم 3.

**4) لو اللابتوب مش جهاز صاحب الريبو:** بعد رقم 2 (الأمر محتاج الملف)، امسح **كل**
نسخ ملف البرود من اللابتوب. الملف ده فيه باسورد داتابيز البرود و `JWT_SECRET`.
(رقم 3 يتعمل ساعتها في ملف صاحب الريبو نفسه.) في **Terminal 1** (`hr-platform`)،
ولازم يطبع `deleted`:

```bash
[ -f deploy/compose.remote-db.yaml ] && rm -f deploy/.env.remote-db && echo deleted || echo 'WRONG TERMINAL: use Terminal 1 (hr-platform)'
```

ونسخة صاحب الريبو اللي قارنت بيها في 12.1 رقم 4: `rm -f` بمسارها الكامل
(<span dir="ltr">`/PATH/TO/OWNER/.env.remote-db`</span>)، وبعدها `ls` بنفس المسار لازم يقول
`No such file or directory`.

- لو فيه بصمات اتسجلت مرتين (6.4 أو 7): اكتبها في الـ issue، وصاحب الريبو يقرر.
- لو جهاز لسه متوجّه للابتوب بالغلط: السيستم هيرفضه، والجهاز هيحتفظ بسجلاته. مفيش حاجة هتضيع.

---

## 13. كل المشاكل في مكان واحد

| المشكلة | السبب المحتمل | اعمل |
|---|---|---|
| مش لاقي الجهاز في `scan` | WiFi بدل كابل، شبكة مختلفة، IP غلط | كابل، نفس الـ subnet، IP من المنيو |
| `comm_key_required` | الجهاز عليه Comm Key، أو الـ <span dir="ltr">`--comm-key`</span> اللي حطيته غلط | اسأل عليه، <span dir="ltr">`--comm-key`</span> |
| `did not answer in time` | الجهاز بيرد UDP بس، أو مشغول | <span dir="ltr">`--udp`</span>، وجرّب بعدين |
| الـ capture مش بيسجل حاجة | firewall، IP اللابتوب اتغير، الجهاز محتاج restart | `sudo ufw allow 8081/tcp`، صحّح الـ IP، restart |
| `Address already in use` لما تشغّل الـ capture | فيه capture تاني شغال على 8081 | Ctrl+C في Terminal 3 الأول |
| `doctor` أو `once` قرأ جهاز مش قصدك | الملف فيه أكتر من جهاز | جهاز واحد في كل ملف (6.1 رقم 4) |
| داشبورد Java على البرود طلّع الكل أو باسورده اتغير | `ADMIN_PASSWORD` في اللابتوب مختلف عن آخر تشغيل | وقّف الـ stack، وبلّغ صاحب الريبو فوراً (12.1 رقم 4) |
| <span dir="ltr">`502 UPSTREAM ERROR`</span> | السيستم واقف | وضع A: `scripts/devices-lab.sh up`؛ وضع B: الـ stack بتاع 12.2 (12.4). الجهاز مش هيضيع حاجة |
| `403` على رفع البصمات | الجهاز مش متخصص أو متوقف | خصّصه (5.3) |
| `413` بيتكرر | أكتر من 5000 سجل في رفعة | اكتبها، وقف، رجّع الجهاز |
| البصمات `UNMATCHED` | الكود مش مربوط بموظف | عادي في وضع A. في B اكتب الأكواد |
| المواعيد غلط بساعة | time zone التخصيص مش زي الجهاز | ماتغيّرش الجهاز. في A: الخطوات التلاتة في 6.4 (`down --wipe` و `seed`، ونسخ التوكن ومسح الـ spool، والتخصيص من تاني) |
| الداشبورد بيقول certificate غير آمن | شهادة محلية | اضغط Advanced وبعدين Proceed |
| الأجهزة مش ظاهرة في الداشبورد | فلتر الشركة مختار | اختار "All companies" (كل الشركات) |
| `SERIAL MISMATCH` | الـ IP لجهاز تاني | صحّح `serial` أو `host` |
| <span dir="ltr">`[Errno 113] No route to host`</span> | الـ ARP فشل: مفيش ولا حزمة وصلت للجهاز، فالجهاز ماردّش عشان ماتسألش. ممكن يكون الـ IP غلط، أو الجهاز مقفول، أو الشبكة ضعيفة | الأمر بيكتب أرقام الشبكة لوحده (واي فاي dBm، حالة ARP، ping ومكرراته) وبيحطها في ورقة النتائج. اتأكد الأول من الـ IP (صورة رقم 3) ومن إن الجهاز شغال؛ لو الاتنين تمام فالمشكلة في الشبكة: قرّب اللابتوب من الراوتر أو اتوصّل بكابل وكرّر الخطوة. **ماتكتبش ده في issue الأجهزة غير لما تتأكد إن الـ IP والجهاز تمام** |
| `not registered` | مش متخصص لشركة التوكن | خصّصه لنفس الشركة |
| `unauthorized` | التوكن غلط أو اتلغى | وضع A: انسخ `lab/agent.token` تاني. وضع B: توكن جديد |
| `chmod 600` في رسالة خطأ | صلاحيات ملف التوكن | `chmod 600` للملف |
| كل بصمة ظاهرة مرتين | الوقت بيتبعت رقم طويل (تجربة 4)، أو `in_out_field` غلط | **ماتبعتش تاني.** شوف 6.4. في وضع B ماتشغّلش `once` تاني |
| USB: `stored` كبير مع إن الجهاز اتقرأ | ترتيب أعمدة مختلف | اكتبها وحط أول 3 سطور في الـ issue بعد تغيير الأكواد. في وضع B اعمل 7 رقم 5 **قبل** الرفع |
| الـ verify فيه `PRE-AGENTS` أو `UNEXPECTED` | الجداول على البرود مش كاملة | **وقّف وضع B**. صاحب الريبو يكمّل 12.1 |
| `hik.pw` أو `agent.token` لسه على اللابتوب | ماتمسحوش، أو اتكتب `rm` في terminal غلط | أمر الخطوة 10 في Terminal 2، ولازم يطبع `deleted` |
| أمر طبع `WRONG TERMINAL` | اتكتب في terminal غلط؛ ماحصلش حاجة | اكتبه تاني في الـ terminal اللي بيقوله |
| برنامج العميل وقف يستلم | الإعدادات مارجعتش زي الأول | قارن بالصورة، restart للجهاز |

---

## ملحق: ورقة النتائج (املاها لكل جهاز)

| البند | النتيجة |
|---|---|
| الشركة / الفرع | |
| التاريخ | |
| الماركة والموديل | |
| السيريال | |
| الـ Firmware | |
| الـ Platform | |
| Push / ADMS موجود؟ | نعم / لا |
| الـ pushver | |
| الـ DeviceType | |
| HTTPS موجود في المنيو؟ | نعم / لا |
| الـ Content-Type | |
| الوقت بيتبعت | تاريخ ووقت / رقم طويل |
| أكبر عدد سجلات في رفعة | |
| ظهر 413؟ | نعم / لا |
| عليه Comm Key؟ | نعم / لا |
| بيرد على | TCP / UDP |
| فرق ساعة الجهاز عن اللابتوب | ...... ثانية |
| الـ Time zone بتاع الجهاز | |
| التوقيت الصيفي (Daylight Saving) | شغال / مقفول |
| الساعة | NTP / يدوي |
| Enable Domain Name موجود؟ | نعم / لا |
| الـ language | |
| الـ PushOptionsFlag | |
| الـ Stamp في رفع ATTLOG | |
| الـ TLS versions | لم يُختبر |
| الـ TimeZone بالدقايق | لم يُختبر |
| الـ Acknowledgement dropped | لم يُختبر |
| سطر ATTLOG: عدد الحقول | |
| سطر ATTLOG: الفاصل | Tab / غيره |
| طول كود الموظف | .... أرقام |
| عدد السجلات على الجهاز | |
| عدد الموظفين | |
| بصمة وصلت خلال | ...... ثانية |
| شلنا الكابل: البصمات وصلت بعد الرجوع؟ | نعم / لا، بعد ...... |
| وقفنا الـ capture 10 دقايق: الجهاز عاد الإرسال؟ | نعم / لا، بعد ...... |
| البصمة دي اتسجلت مرة واحدة؟ | نعم / لا |
| الدخول | رقم .... |
| الخروج | رقم .... |
| الـ in_out_field الصح | punch / status |
| توزيع أكواد الدخول/الخروج في سجل الجهاز | punch {...}، status {...} |
| الموظفين بيدوسوا زرار الدخول/الخروج؟ | نعم / لا |
| USB: ترتيب الأعمدة زي الـ Push؟ | نعم / لا |
| Hikvision: أكواد الحضور اللي ظهرت | |
| حالة الشبكة وقت المشكلة | الواي فاي ... dBm، ARP ...، ping ... |
| مشاكل تانية | |

</div>
