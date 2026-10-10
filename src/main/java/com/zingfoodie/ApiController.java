package com.zingfoodie;


import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api")
public class ApiController {
    private final JdbcTemplate db;
    private final SecureRandom random = new SecureRandom();
    public ApiController(JdbcTemplate db) { this.db = db; initDb(); }

    private void initDb() {
        db.execute("CREATE TABLE IF NOT EXISTS users(id INTEGER PRIMARY KEY AUTOINCREMENT, username TEXT UNIQUE NOT NULL, full_name TEXT NOT NULL, role TEXT NOT NULL, password_hash TEXT NOT NULL, campus TEXT NOT NULL, created_at TEXT NOT NULL)");
        db.execute("CREATE TABLE IF NOT EXISTS products(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, category TEXT NOT NULL, vendor TEXT NOT NULL, price REAL NOT NULL CHECK(price>=0), stock INTEGER NOT NULL CHECK(stock>=0), emoji TEXT NOT NULL, description TEXT NOT NULL, prep INTEGER NOT NULL, active INTEGER NOT NULL DEFAULT 1)");
        db.execute("CREATE TABLE IF NOT EXISTS orders(id TEXT PRIMARY KEY, user_id INTEGER NOT NULL, name TEXT NOT NULL, role TEXT NOT NULL, student_id TEXT, campus TEXT NOT NULL, building TEXT, room TEXT, mode TEXT NOT NULL, delivery_time TEXT NOT NULL, notes TEXT, subtotal REAL NOT NULL, fee REAL NOT NULL, total REAL NOT NULL, status TEXT NOT NULL, qr_token TEXT NOT NULL UNIQUE, qr_used INTEGER NOT NULL DEFAULT 0, created_at TEXT NOT NULL, FOREIGN KEY(user_id) REFERENCES users(id))");
        db.execute("CREATE TABLE IF NOT EXISTS order_items(id INTEGER PRIMARY KEY AUTOINCREMENT, order_id TEXT NOT NULL, product_id INTEGER NOT NULL, product_name TEXT NOT NULL, vendor TEXT NOT NULL, price REAL NOT NULL, quantity INTEGER NOT NULL, FOREIGN KEY(order_id) REFERENCES orders(id))");
        Integer n = db.queryForObject("SELECT COUNT(*) FROM products", Integer.class);
        if (n == null || n == 0) {
            Object[][] p = {{"Chicken Rice Meal","Meals","Campus Kitchen",75,28,"🍗","Savory chicken, rice, and house sauce.",15},{"Porksilog","Meals","Tita May’s Stall",80,18,"🍳","Classic silog meal for a filling break.",15},{"Beef Tapa Bowl","Meals","Campus Kitchen",95,12,"🥩","Tender beef tapa with garlic rice.",18},{"Pancit Canton","Meals","Noodle Corner",55,20,"🍜","Stir-fried noodles with vegetables.",12},{"Cheese Burger","Snacks","Grill Station",65,16,"🍔","Grilled patty, cheese, and fresh lettuce.",12},{"Chicken Siomai (4 pcs)","Snacks","Noodle Corner",40,25,"🥟","Steamed bites with chili-garlic sauce.",8},{"Fries","Snacks","Grill Station",45,22,"🍟","Golden, crispy fries with seasoning.",8},{"Iced Tea","Drinks","Cool Cup",25,40,"🧋","Refreshing house-brewed iced tea.",3},{"Iced Coffee","Drinks","Cool Cup",55,24,"☕","Chilled coffee for your study session.",4},{"Bottled Water","Drinks","Cool Cup",20,50,"💧","Cold drinking water, 500 ml.",2},{"Chocolate Brownie","Desserts","Sweet Corner",35,14,"🍫","Rich chocolate brownie square.",2},{"Leche Flan Cup","Desserts","Sweet Corner",40,10,"🍮","Creamy caramel custard dessert.",2}};
            for (Object[] x : p) db.update("INSERT INTO products(name,category,vendor,price,stock,emoji,description,prep) VALUES(?,?,?,?,?,?,?,?)",x);
        }
        String staffUser = System.getenv().getOrDefault("STAFF_USERNAME", "staff");
        String staffPass = System.getenv().getOrDefault("STAFF_PASSWORD", "");
        Integer staffCount = db.queryForObject("SELECT COUNT(*) FROM users WHERE username=?", Integer.class, staffUser);
        if ((staffCount == null || staffCount == 0) && !staffPass.isBlank()) {
            db.update("INSERT INTO users(username,full_name,role,password_hash,campus,created_at) VALUES(?,?,?,?,?,?)", staffUser,"Campus Administrator","STAFF",hashPassword(staffPass),"Main Campus",LocalDateTime.now().toString());
        }
    }

    private Map<String,Object> user(HttpSession s) { Object u=s.getAttribute("user"); return u instanceof Map ? (Map<String,Object>)u : null; }
    private Map<String,Object> requireUser(HttpSession s) { Map<String,Object> u=user(s); if(u==null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Please log in first."); return u; }
    private Map<String,Object> requireStaff(HttpSession s) { Map<String,Object> u=requireUser(s); if(!"STAFF".equals(u.get("role"))) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Staff access required."); return u; }
    private String hashPassword(String pass) { try { byte[] salt=new byte[16]; random.nextBytes(salt); PBEKeySpec spec=new PBEKeySpec(pass.toCharArray(),salt,120000,256); byte[] key=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); return Base64.getEncoder().encodeToString(salt)+":"+Base64.getEncoder().encodeToString(key); } catch(Exception e){ throw new RuntimeException(e); } }
    private boolean checkPassword(String pass,String stored) { try { String[] a=stored.split(":"); PBEKeySpec spec=new PBEKeySpec(pass.toCharArray(),Base64.getDecoder().decode(a[0]),120000,256); byte[] key=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); return MessageDigest.isEqual(key,Base64.getDecoder().decode(a[1])); } catch(Exception e){ return false; } }
    private Map<String,Object> publicUser(Map<String,Object> u) { Map<String,Object> x=new LinkedHashMap<>(); x.put("id",u.get("id")); x.put("username",u.get("username")); x.put("name",u.get("name")); x.put("role",u.get("role")); x.put("campus",u.get("campus")); return x; }
    private Map<String,Object> productRow(Map<String,Object> p) { Map<String,Object> x=new LinkedHashMap<>(p); x.put("desc",x.remove("description")); return x; }
    private List<Map<String,Object>> menu(boolean includeInactive) { return db.queryForList("SELECT id,name,category,vendor,price,stock,emoji,description,prep FROM products WHERE active=1 ORDER BY id").stream().map(this::productRow).toList(); }
    private Map<String,Object> orderRow(String id, boolean includeItems) {
        List<Map<String,Object>> rows=db.queryForList("SELECT id,user_id,name,role,student_id,campus,building,room,mode,delivery_time,notes,subtotal,fee,total,status,qr_used,created_at FROM orders WHERE id=?",id);
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Order not found.");
        Map<String,Object> o=new LinkedHashMap<>(rows.get(0)); o.put("time",o.remove("delivery_time")); o.put("studentId",o.remove("student_id")); o.put("qrUsed",((Number)o.remove("qr_used")).intValue()==1); o.put("date",o.remove("created_at"));
        if(includeItems) o.put("items",db.queryForList("SELECT product_id AS id,product_name AS name,vendor,price,quantity AS qty FROM order_items WHERE order_id=?",id));
        return o;
    }
    @GetMapping("/health") public Map<String,Object> health(){return Map.of("status","ok","app","ZingFoodie Campus");}
    @GetMapping("/session") public Map<String,Object> session(HttpSession s){Map<String,Object> u=user(s);return Map.of("authenticated",u!=null,"user",u==null?Map.of():publicUser(u));}
    @PostMapping("/register") public Map<String,Object> register(@RequestBody Map<String,Object> b,HttpSession s){
        String username=str(b,"username").toLowerCase(Locale.ROOT), name=str(b,"fullName"), pass=str(b,"password"), campus=str(b,"campus"), role=str(b,"role").toUpperCase(Locale.ROOT);
        if(!Set.of("STUDENT","TEACHER","FACULTY").contains(role)) role="STUDENT";
        if(username.length()<4||name.length()<2||pass.length()<8||campus.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Enter a username (4+ characters), full name, campus, and password (8+ characters).");
        if(db.queryForObject("SELECT COUNT(*) FROM users WHERE username=?",Integer.class,username)>0) throw new ResponseStatusException(HttpStatus.CONFLICT,"That username is already registered.");
        db.update("INSERT INTO users(username,full_name,role,password_hash,campus,created_at) VALUES(?,?,?,?,?,?)",username,name,role,hashPassword(pass),campus,LocalDateTime.now().toString());
        Map<String,Object> u=db.queryForMap("SELECT id,username,full_name AS name,role,campus FROM users WHERE username=?",username);s.setAttribute("user",u);return Map.of("user",publicUser(u));
    }
    @PostMapping("/login") public Map<String,Object> login(@RequestBody Map<String,Object> b,HttpSession s){String username=str(b,"username").toLowerCase(Locale.ROOT),pass=str(b,"password");List<Map<String,Object>> rows=db.queryForList("SELECT id,username,full_name AS name,role,campus,password_hash FROM users WHERE username=?",username);if(rows.isEmpty()||!checkPassword(pass,(String)rows.get(0).get("password_hash")))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Incorrect username or password.");Map<String,Object> u=rows.get(0);u.remove("password_hash");s.setAttribute("user",u);return Map.of("user",publicUser(u));}
    @PostMapping("/logout") public Map<String,Object> logout(HttpSession s){s.invalidate();return Map.of("ok",true);}
    @GetMapping("/menu") public List<Map<String,Object>> getMenu(){return menu(false);}
    @GetMapping("/orders") public List<Map<String,Object>> myOrders(HttpSession s){Map<String,Object> u=requireUser(s);List<String> ids=db.queryForList("SELECT id FROM orders WHERE user_id=? ORDER BY created_at DESC",String.class,u.get("id"));return ids.stream().map(id->orderRow(id,true)).toList();}
    @Transactional
    @PostMapping("/orders") public Map<String,Object> createOrder(@RequestBody Map<String,Object> b,HttpSession s){
        Map<String,Object> u=requireUser(s);String mode=str(b,"mode"),campus=str(b,"campus"),building=str(b,"building"),room=str(b,"room");
        if(campus.isBlank()||(!mode.equals("Pickup counter")&&(building.isBlank()||room.isBlank())))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Campus, building and classroom/section are required for delivery.");
        Object raw=b.get("items");if(!(raw instanceof List<?> list)||list.isEmpty())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Your cart is empty.");
        String id="ZF-"+UUID.randomUUID().toString().substring(0,8).toUpperCase(Locale.ROOT),token=UUID.randomUUID().toString();double subtotal=0;List<Map<String,Object>> items=new ArrayList<>();
        for(Object entry:list){if(!(entry instanceof Map<?,?> m))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid cart.");int pid=((Number)m.get("id")).intValue(),qty=((Number)m.get("qty")).intValue();if(qty<1||qty>99)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid item quantity.");List<Map<String,Object>> p=db.queryForList("SELECT id,name,vendor,price,stock FROM products WHERE id=? AND active=1",pid);if(p.isEmpty())throw new ResponseStatusException(HttpStatus.CONFLICT,"A menu item is no longer available.");Map<String,Object> pr=p.get(0);if(((Number)pr.get("stock")).intValue()<qty)throw new ResponseStatusException(HttpStatus.CONFLICT,"Not enough stock for "+pr.get("name"));double price=((Number)pr.get("price")).doubleValue();subtotal+=price*qty;items.add(Map.of("id",pid,"name",pr.get("name"),"vendor",pr.get("vendor"),"price",price,"qty",qty));}
            double fee=mode.equals("Pickup counter")?0:10,total=subtotal+fee;
            db.update("INSERT INTO orders(id,user_id,name,role,student_id,campus,building,room,mode,delivery_time,notes,subtotal,fee,total,status,qr_token,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",id,u.get("id"),str(b,"name"),("STAFF".equals(u.get("role"))?"Campus Staff":String.valueOf(u.get("role").toString().charAt(0))+u.get("role").toString().substring(1).toLowerCase(Locale.ROOT)),str(b,"studentId"),campus,building,room,mode,str(b,"time"),str(b,"notes"),subtotal,fee,total,"PENDING",token,LocalDateTime.now().toString());
        for(Map<String,Object> it:items){int changed=db.update("UPDATE products SET stock=stock-? WHERE id=? AND stock>=?",it.get("qty"),it.get("id"),it.get("qty"));if(changed!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"Stock changed while checking out. Please review your cart.");db.update("INSERT INTO order_items(order_id,product_id,product_name,vendor,price,quantity) VALUES(?,?,?,?,?,?)",id,it.get("id"),it.get("name"),it.get("vendor"),it.get("price"),it.get("qty"));}
        return orderRow(id,true);
    }
    @Transactional
    @PostMapping("/orders/{id}/cancel") public Map<String,Object> cancel(@PathVariable String id,HttpSession s){Map<String,Object> u=requireUser(s);Map<String,Object> o=orderRow(id,true);if(!Objects.equals(((Number)o.get("user_id")).intValue(),((Number)u.get("id")).intValue()))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"This order belongs to another account.");if(!"PENDING".equals(o.get("status")))throw new ResponseStatusException(HttpStatus.CONFLICT,"Only pending orders can be cancelled.");int changed=db.update("UPDATE orders SET status='CANCELLED' WHERE id=? AND status='PENDING'",id);if(changed!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"Order status changed. Refresh and try again.");for(Map<String,Object> it:(List<Map<String,Object>>)o.get("items"))db.update("UPDATE products SET stock=stock+? WHERE id=?",it.get("qty"),it.get("id"));return orderRow(id,true);}
    @GetMapping("/staff/orders") public List<Map<String,Object>> allOrders(HttpSession s){requireStaff(s);return db.queryForList("SELECT id FROM orders ORDER BY created_at DESC").stream().map(x->orderRow((String)x.get("id"),true)).toList();}
    @Transactional
    @PatchMapping("/staff/orders/{id}") public Map<String,Object> status(@PathVariable String id,@RequestBody Map<String,Object> b,HttpSession s){requireStaff(s);String st=str(b,"status");if(!Set.of("PENDING","PREPARING","READY FOR PICKUP","OUT FOR DELIVERY","CANCELLED").contains(st))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid order status.");Map<String,Object> o=orderRow(id,true);if(Boolean.TRUE.equals(o.get("qrUsed"))||"COMPLETED".equals(o.get("status"))&& !"COMPLETED".equals(st))throw new ResponseStatusException(HttpStatus.CONFLICT,"A completed order cannot be changed again.");if("CANCELLED".equals(st)&&!"PENDING".equals(o.get("status")))throw new ResponseStatusException(HttpStatus.CONFLICT,"Only pending orders can be cancelled.");if("CANCELLED".equals(st)){for(Map<String,Object> it:(List<Map<String,Object>>)o.get("items"))db.update("UPDATE products SET stock=stock+? WHERE id=?",it.get("qty"),it.get("id"));}db.update("UPDATE orders SET status=?,qr_used=CASE WHEN ?='COMPLETED' THEN 1 ELSE qr_used END WHERE id=?",st,st,id);return orderRow(id,true);}
    @PostMapping("/staff/orders/{id}/verify") public Map<String,Object> verify(@PathVariable String id,@RequestBody Map<String,Object> b,HttpSession s){requireStaff(s);String token=str(b,"token");Map<String,Object> o=orderRow(id,true);if(!Set.of("READY FOR PICKUP","OUT FOR DELIVERY").contains(String.valueOf(o.get("status"))))throw new ResponseStatusException(HttpStatus.CONFLICT,"Order must be ready for pickup or out for delivery before QR verification.");String actual=db.queryForObject("SELECT qr_token FROM orders WHERE id=?",String.class,id);if(!MessageDigest.isEqual(actual.getBytes(StandardCharsets.UTF_8),token.getBytes(StandardCharsets.UTF_8))||Boolean.TRUE.equals(o.get("qrUsed"))||"CANCELLED".equals(o.get("status")))throw new ResponseStatusException(HttpStatus.CONFLICT,"Invalid or already-used QR token.");db.update("UPDATE orders SET status='COMPLETED',qr_used=1 WHERE id=?",id);return orderRow(id,true);}
    @GetMapping(value="/orders/{id}/qr",produces=MediaType.IMAGE_PNG_VALUE) public ResponseEntity<byte[]> qr(@PathVariable String id,HttpSession s)throws Exception{Map<String,Object> u=requireUser(s);Map<String,Object> o=orderRow(id,true);boolean owner=Objects.equals(((Number)o.get("user_id")).intValue(),((Number)u.get("id")).intValue());if(!owner&&"STAFF".equals(u.get("role"))==false)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Not authorized.");String token=db.queryForObject("SELECT qr_token FROM orders WHERE id=?",String.class,id);BitMatrix matrix=new QRCodeWriter().encode(id+":"+token,BarcodeFormat.QR_CODE,240,240);ByteArrayOutputStream out=new ByteArrayOutputStream();MatrixToImageWriter.writeToStream(matrix,"PNG",out);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(out.toByteArray());}
    @GetMapping("/staff/products") public List<Map<String,Object>> staffProducts(HttpSession s){requireStaff(s);return db.queryForList("SELECT id,name,category,vendor,price,stock,emoji,description,prep FROM products ORDER BY id").stream().map(this::productRow).toList();}
    @PostMapping("/staff/products") public Map<String,Object> addProduct(@RequestBody Map<String,Object> b,HttpSession s){requireStaff(s);String name=str(b,"name"),vendor=str(b,"vendor"),category=str(b,"category"),desc=str(b,"desc"),emoji=str(b,"emoji");double price=num(b,"price");int stock=(int)num(b,"stock");if(name.isBlank()||vendor.isBlank()||price<=0||stock<0||!Set.of("Meals","Snacks","Drinks","Desserts").contains(category))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Check product name, vendor, category, price and stock.");db.update("INSERT INTO products(name,category,vendor,price,stock,emoji,description,prep) VALUES(?,?,?,?,?,?,?,10)",name,category,vendor,price,stock,emoji.isBlank()?"🍱":emoji,desc.isBlank()?"Freshly prepared on campus.":desc);return db.queryForMap("SELECT id,name,category,vendor,price,stock,emoji,description AS `desc`,prep FROM products WHERE id=last_insert_rowid()");}
    @PatchMapping("/staff/products/{id}/stock") public Map<String,Object> stock(@PathVariable int id,@RequestBody Map<String,Object> b,HttpSession s){requireStaff(s);int stock=(int)num(b,"stock");if(stock<0||num(b,"stock")!=stock)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Stock must be a non-negative whole number.");int n=db.update("UPDATE products SET stock=? WHERE id=?",stock,id);if(n==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Product not found.");return db.queryForMap("SELECT id,name,category,vendor,price,stock,emoji,description AS `desc`,prep FROM products WHERE id=?",id);}
    @GetMapping("/staff/report") public Map<String,Object> report(HttpSession s){requireStaff(s);Map<String,Object> summary=db.queryForMap("SELECT COUNT(*) AS orders,COALESCE(SUM(CASE WHEN status!='CANCELLED' THEN subtotal ELSE 0 END),0) AS gross FROM orders");summary.put("itemsSold",db.queryForObject("SELECT COALESCE(SUM(oi.quantity),0) FROM order_items oi JOIN orders o ON o.id=oi.order_id WHERE o.status!='CANCELLED'",Integer.class));summary.put("byProduct",db.queryForList("SELECT oi.product_name AS name,SUM(oi.quantity) AS qty,SUM(oi.price*oi.quantity) AS sales FROM order_items oi JOIN orders o ON o.id=oi.order_id WHERE o.status!='CANCELLED' GROUP BY oi.product_id ORDER BY sales DESC"));return summary;}
    private String str(Map<String,Object>b,String k){Object v=b.get(k);return v==null?"":String.valueOf(v).trim();}private double num(Map<String,Object>b,String k){Object v=b.get(k);try{return v instanceof Number n?n.doubleValue():Double.parseDouble(String.valueOf(v));}catch(Exception e){return -1;}}

    @PostMapping("/firebase/verify")
    public Map<String, Object> verifyFirebaseToken(
            @RequestBody Map<String, Object> body) {

        String idToken = str(body, "idToken");

        if (idToken.isBlank()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Firebase ID token is required."
            );
        }

        if (FirebaseApp.getApps().isEmpty()) {
            throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Firebase is not configured."
            );
        }

        try {
            FirebaseToken verified =
                FirebaseAuth.getInstance().verifyIdToken(idToken);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("authenticated", true);
            result.put("uid", verified.getUid());

            Object email = verified.getClaims().get("email");
            if (email != null) {
                result.put("email", email);
            }

            result.put(
                "emailVerified",
                Boolean.TRUE.equals(
                    verified.getClaims().get("email_verified")
                )
            );

            return result;

        } catch (com.google.firebase.auth.FirebaseAuthException e) {
            throw new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "Firebase ID token is invalid or expired."
            );
        }
    }
}
