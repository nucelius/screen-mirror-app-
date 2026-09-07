# Screen Mirror (WiFi, local network)

แอพ Android สำหรับสะท้อนหน้าจอจากอุปกรณ์หนึ่งไปอีกอุปกรณ์หนึ่ง ผ่าน WiFi
ในวง LAN เดียวกัน (ไม่ผ่านอินเทอร์เน็ต ไม่มี cloud server)

## วิธีเปิดโปรเจกต์

1. เปิด Android Studio (แนะนำเวอร์ชันล่าสุด, Hedgehog ขึ้นไป)
2. File → Open → เลือกโฟลเดอร์ `ScreenMirror` นี้
3. รอ Gradle sync (ครั้งแรกอาจใช้เวลาสักครู่เพราะต้องโหลด dependency)
4. กด Run บนอุปกรณ์จริง 2 เครื่อง (แนะนำให้ใช้เครื่องจริง ไม่ใช่ emulator
   เพราะ MediaProjection และ NSD/mDNS ต้องพึ่งพา WiFi radio จริง)

## วิธีสร้างไฟล์ APK ฟรี ด้วย GitHub Actions (ไม่ต้องลงโปรแกรมอะไรเลย)

โปรเจกต์นี้มีไฟล์ `.github/workflows/build-apk.yml` เตรียมไว้ให้แล้ว
ใช้ GitHub build ให้ฟรีบนคลาวด์ ขั้นตอน:

1. สร้าง repository ใหม่บน GitHub (public หรือ private ก็ได้) เช่น
   `screen-mirror-app`
2. อัปโหลดโค้ดทั้งหมดในโฟลเดอร์ `ScreenMirror` นี้ขึ้น repo
   (ลากไฟล์ผ่านหน้าเว็บ GitHub ได้เลย หรือใช้ `git push` ถ้าถนัด)
3. ไปที่แท็บ **Actions** ของ repo → จะเห็น workflow ชื่อ "Build APK"
   รันอัตโนมัติ (หรือกด "Run workflow" เพื่อรันเองถ้ายังไม่รัน)
4. รอประมาณ 5-10 นาที ให้ build เสร็จ (สถานะจะขึ้นเครื่องหมายถูกสีเขียว)
5. คลิกเข้าไปในรัน (run) ที่เสร็จแล้ว → เลื่อนลงมาส่วน **Artifacts**
   → ดาวน์โหลด `ScreenMirror-debug-apk` (เป็นไฟล์ .zip ข้างในมี .apk)
6. แตกซิป จะได้ไฟล์ `app-debug.apk` — โอนเข้าโทรศัพท์ (ผ่าน LINE, Google
   Drive, สาย USB ฯลฯ) แล้วเปิดเพื่อติดตั้ง

**หมายเหตุ:** โทรศัพท์ต้องเปิด "อนุญาตติดตั้งจากแหล่งที่ไม่รู้จัก" (Install
unknown apps) สำหรับแอพที่ใช้เปิดไฟล์ APK นี้ก่อน (เช่น Files, Chrome,
LINE) เพราะเป็น APK ที่ไม่ได้ลงจาก Play Store — เป็นเรื่องปกติสำหรับแอพที่
build เอง ไม่ใช่แอพอันตราย แต่ Android จะเตือนเป็นค่าเริ่มต้นเพื่อความ
ปลอดภัย

APK ที่ได้จากขั้นตอนนี้เป็น **debug build** (เหมาะสำหรับทดสอบเอง/ติดตั้ง
เข้าเครื่องตัวเอง) ถ้าจะอัปขึ้น Google Play จริงต้องทำ **release build**
ที่เซ็นด้วย keystore ของตัวเอง ซึ่งเป็นขั้นตอนถัดไป (บอกได้ถ้าต้องการให้
ช่วยตรงนี้ด้วย)

## วิธีใช้งาน

1. ทั้งสองเครื่องต้องต่อ **WiFi วงเดียวกัน** (SSID เดียวกัน)
2. เครื่องที่ 1 (ฝั่งส่ง): เปิดแอพ → กด "เริ่มแชร์หน้าจอ" → กด "Start now"
   ในป๊อปอัปของระบบ Android
3. เครื่องที่ 2 (ฝั่งรับ): เปิดแอพ → กด "รับหน้าจอ" → รอสักครู่ให้ค้นหา
   และเชื่อมต่ออัตโนมัติผ่าน NSD (mDNS)
4. หน้าจอของเครื่อง 1 จะแสดงบนเครื่อง 2

## สถาปัตยกรรมโดยสรุป

- **MainActivity** — เลือกโหมด ส่ง/รับ ขอ permission ที่จำเป็น
- **SenderService** — Foreground Service ที่:
  - ใช้ `MediaProjection` แคปหน้าจอ
  - เข้ารหัสด้วย `MediaCodec` (H.264) ผ่าน Surface โดยตรง (ไม่ต้อง copy bitmap)
  - ประกาศตัวเองบนเครือข่ายผ่าน `NsdManager` (`_screenmirror._tcp.`)
  - ส่งข้อมูลผ่าน TCP socket แบบ length-prefixed frame
- **ReceiverActivity** — ค้นหา service ผ่าน NSD, เชื่อมต่อ, ถอดรหัสด้วย
  `MediaCodec` decoder และ render ตรงลง `SurfaceView`

โปรโตคอลเรียบง่ายมาก (ไม่มี handshake ซับซ้อน, ไม่มี retransmission,
ไม่มี adaptive bitrate) — เหมาะสำหรับ MVP / เรียนรู้ ไม่ใช่ production-grade
streaming stack แบบ WebRTC

## ข้อจำกัดที่ควรรู้ (สำคัญ)

โค้ดนี้ผมเขียนให้ตามหลักการที่ถูกต้องของ Android API แต่ **ยังไม่ได้ผ่านการ
คอมไพล์หรือทดสอบจริง** เพราะสภาพแวดล้อมนี้ไม่มี Android SDK ให้ build เป็น
APK ได้ ดังนั้นเมื่อเปิดใน Android Studio ครั้งแรก อาจเจอเรื่องพวกนี้:

- Android Studio อาจเสนออัปเดต AGP/Gradle/Kotlin ให้เข้ากับเวอร์ชัน Studio
  ที่ใช้ — กด upgrade ตามที่แนะนำได้เลย
- ยังไม่มีไอคอนแอพ (mipmap/ic_launcher) — ใส่ก่อนขึ้น Play Store จริง
- รองรับผู้รับแค่ 1 เครื่องพร้อมกันต่อครั้ง (MVP)
- ไม่มี retry/reconnect อัตโนมัติถ้าหลุดกลางทาง
- ไม่มีการเข้ารหัสข้อมูล stream (เหมาะสำหรับ trusted LAN เท่านั้น)
- ความละเอียด/บิตเรตตั้งค่าคงที่ในโค้ด (1280x720, 4 Mbps) ปรับได้ใน
  `SenderService.kt`

## ก่อนขึ้น Google Play

- [ ] ใส่ app icon จริง (adaptive icon แนะนำ)
- [ ] เขียน **Privacy Policy** — บังคับเพราะแอพขอ sensitive permission
      (MediaProjection = จับภาพหน้าจอผู้ใช้) ต้องมีลิงก์ policy ในหน้า
      Play Console
- [ ] กรอกแบบฟอร์มชี้แจงการใช้งาน "Screen recording" ใน Play Console
      Data Safety section ให้ตรงกับสิ่งที่แอพทำจริง (ไม่เก็บ/ไม่ส่งข้อมูล
      ออกนอกเครื่อง ถ้าไม่ได้ทำ cloud sync)
- [ ] ทดสอบบนอุปกรณ์หลายยี่ห้อ (โดยเฉพาะ Samsung/Xiaomi ที่มัก custom
      พฤติกรรม background service)
- [ ] พิจารณาเพิ่ม runtime permission request สำหรับ
      `NEARBY_WIFI_DEVICES` บน Android 13+ ถ้าพบปัญหาการค้นหาอุปกรณ์
- [ ] ทดสอบ edge case: WiFi หลุด, สลับแอพระหว่างแชร์, หมุนจอ

## ไฟล์สำคัญที่จะปรับแก้บ่อย

| ต้องการปรับ | ไฟล์ |
|---|---|
| ความละเอียด/บิตเรต | `SenderService.kt` (ตัวแปร `width`, `height`, `bitRate`) |
| พอร์ตเครือข่าย | `SenderService.kt` (`PORT`) |
| ชื่อ service ที่ค้นหากันเจอ | `SenderService.kt` / `ReceiverActivity.kt` (`_screenmirror._tcp.`) |
| หน้าตาแอพ | ไฟล์ใน `res/layout/` |
