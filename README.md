# ZingFoodie Campus

A full-stack campus food-ordering system following the Group 5 proposal: HTML/CSS/JavaScript, Java Spring Boot backend, SQLite via JDBC, QR-code receipts, GitHub source control and Render deployment.

## Features

- Student, teacher and faculty account registration/login with salted PBKDF2 password hashes.
- Staff-only dashboard; public registration cannot create staff accounts.
- Live menu search/categories, sample campus food stalls, prices and server-side stock availability.
- Cart and checkout; delivery requires campus, building and classroom/section or office. Pickup is available too.
- ₱10 classroom/office delivery fee; pickup has no delivery fee.
- Server-side stock checks and deduction at checkout; stock is restored for pending-order cancellations.
- Per-account order history and status tracking: PENDING → PREPARING → READY FOR PICKUP / OUT FOR DELIVERY → COMPLETED.
- Server-generated QR receipt image, one-time staff verification endpoint, printable receipt.
- Staff can add menu items, replenish stock, update order status and view sales reports.
- SQLite storage and Render deployment configuration.

## Run locally

Requirements: Java 21 and Maven 3.9+.

```bash
export STAFF_USERNAME=campusadmin
export STAFF_PASSWORD='Choose-A-Long-Unique-Password'
mvn spring-boot:run
```

Open **http://localhost:8080**. Do not open `index.html` directly: it calls the Java API served by this app. SQLite is stored in `./zingfoodie.db` by default. To use a different file, set `DB_PATH` to a file path inside an existing writable directory.

### Staff login

Set `STAFF_USERNAME` and `STAFF_PASSWORD` before the first startup. On initial startup the app creates that staff account if the username is not already in the database. Use a strong, unique password. If you change the environment password after the account has already been created, the stored account password does not change automatically.

## Deploy to Render

1. Push the entire project folder to a GitHub repository, including `pom.xml`, `Dockerfile`, `render.yaml`, and `src/`.
2. In Render, select **New + → Blueprint** and connect that repository.
3. Set a strong secret for `STAFF_PASSWORD` when prompted. The configured default staff username is `campusadmin`; change it before first deployment if desired.
4. Deploy. The Dockerfile builds the Java app, which serves the frontend and API on the same domain.
5. Check `/api/health` for a response like `{"status":"ok","app":"ZingFoodie Campus"}`.

The included blueprint uses a Render Starter web service with a 1 GB persistent disk at `/var/data` so the SQLite database can persist. This may incur a charge; review Render's current pricing before deploying. Do not remove the disk if you need database persistence. For larger production deployments, use a managed database instead of SQLite on a local disk.

## API overview

- `POST /api/register`, `POST /api/login`, `POST /api/logout`, `GET /api/session`
- `GET /api/menu`
- `GET /api/orders`, `POST /api/orders`, `POST /api/orders/{id}/cancel`
- `GET /api/orders/{id}/qr`
- Staff only: `GET /api/staff/orders`, `PATCH /api/staff/orders/{id}`, `POST /api/staff/orders/{id}/verify`, `GET/POST /api/staff/products`, `PATCH /api/staff/products/{id}/stock`, `GET /api/staff/report`

## Before a real campus launch

- Replace the sample products, prices, vendors, time slots and campus names with approved information.
- QR content is `ORDER-ID:TOKEN`; staff can scan it and paste the decoded text into the verification prompt. Verification is single-use. A dedicated camera scanner UI can be added later.
- This class-project system does not process online payments, send SMS/email alerts, or verify school IDs against an official school directory. Have campus administrators review privacy, data retention and delivery rules before using real student data. Serve it over HTTPS and keep the staff password secret.
