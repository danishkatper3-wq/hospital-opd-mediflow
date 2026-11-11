package opd.db;

import opd.model.Doctor;
import opd.model.Patient;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Persistent storage (embedded H2 SQL database, file: ./data/opd.mv.db).
 * Stores doctors, patients and the activity log so nothing is lost when the program is closed.
 */
public class Database {

    private final Connection c;

    private static final String COLS =
            "token, name, age, gender, phone, complaint, doctor_id, emergency, status, "
          + "enq_seq, registered_at, called_at, finished_at";

    public Database(String path) throws SQLException {
        try {
            Class.forName("org.h2.Driver");
        } catch (ClassNotFoundException e) {
            throw new SQLException("H2 driver not found. Put lib/h2.jar on the classpath.", e);
        }
        c = DriverManager.getConnection("jdbc:h2:file:" + path, "sa", "");
        init();
    }

    private void init() throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS doctors ("
                    + "id INT AUTO_INCREMENT PRIMARY KEY, "
                    + "name VARCHAR(80) NOT NULL, department VARCHAR(60) NOT NULL, "
                    + "room VARCHAR(20), avg_minutes INT DEFAULT 10)");
            s.execute("CREATE TABLE IF NOT EXISTS patients ("
                    + "token INT AUTO_INCREMENT PRIMARY KEY, "
                    + "name VARCHAR(80) NOT NULL, age INT, gender VARCHAR(10), "
                    + "phone VARCHAR(30), complaint VARCHAR(200), doctor_id INT NOT NULL, "
                    + "emergency BOOLEAN DEFAULT FALSE, status VARCHAR(12) NOT NULL, "
                    + "enq_seq BIGINT, registered_at TIMESTAMP, called_at TIMESTAMP, "
                    + "finished_at TIMESTAMP)");
            s.execute("CREATE TABLE IF NOT EXISTS ops_log ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY, ts TIMESTAMP, "
                    + "op VARCHAR(20), detail VARCHAR(300))");
        }
    }

    // ------------------------------------------------------------ helpers
    private static Timestamp ts(LocalDateTime t) { return t == null ? null : Timestamp.valueOf(t); }
    private static LocalDateTime ldt(Timestamp t) { return t == null ? null : t.toLocalDateTime(); }

    private static Patient read(ResultSet r) throws SQLException {
        Patient p = new Patient();
        p.token = r.getInt("token");
        p.name = r.getString("name");
        p.age = r.getInt("age");
        p.gender = r.getString("gender");
        p.phone = r.getString("phone");
        p.complaint = r.getString("complaint");
        p.doctorId = r.getInt("doctor_id");
        p.emergency = r.getBoolean("emergency");
        p.status = r.getString("status");
        p.enqSeq = r.getLong("enq_seq");
        p.registeredAt = ldt(r.getTimestamp("registered_at"));
        p.calledAt = ldt(r.getTimestamp("called_at"));
        p.finishedAt = ldt(r.getTimestamp("finished_at"));
        return p;
    }

    private List<Patient> query(String where, Object... args) throws SQLException {
        List<Patient> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT " + COLS + " FROM patients " + where)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) out.add(read(r));
            }
        }
        return out;
    }

    // ------------------------------------------------------------ doctors
    public synchronized List<Doctor> doctors() throws SQLException {
        List<Doctor> out = new ArrayList<>();
        try (Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT id, name, department, room, avg_minutes FROM doctors ORDER BY id")) {
            while (r.next())
                out.add(new Doctor(r.getInt(1), r.getString(2), r.getString(3), r.getString(4), r.getInt(5)));
        }
        return out;
    }

    public synchronized int addDoctor(String name, String dept, String room, int avg) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO doctors(name, department, room, avg_minutes) VALUES (?,?,?,?)",
                new String[]{"ID"})) {
            ps.setString(1, name); ps.setString(2, dept); ps.setString(3, room); ps.setInt(4, avg);
            ps.executeUpdate();
            try (ResultSet k = ps.getGeneratedKeys()) { k.next(); return k.getInt(1); }
        }
    }

    // ----------------------------------------------------------- patients
    public synchronized int insertPatient(Patient p) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO patients(name, age, gender, phone, complaint, doctor_id, emergency, "
              + "status, enq_seq, registered_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                new String[]{"TOKEN"})) {
            ps.setString(1, p.name); ps.setInt(2, p.age); ps.setString(3, p.gender);
            ps.setString(4, p.phone); ps.setString(5, p.complaint); ps.setInt(6, p.doctorId);
            ps.setBoolean(7, p.emergency); ps.setString(8, p.status); ps.setLong(9, p.enqSeq);
            ps.setTimestamp(10, ts(p.registeredAt));
            ps.executeUpdate();
            try (ResultSet k = ps.getGeneratedKeys()) { k.next(); return k.getInt(1); }
        }
    }

    /** Update the editable fields (and waiting-line placement) of a patient. */
    public synchronized void updatePatient(Patient p) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE patients SET name=?, age=?, gender=?, phone=?, complaint=?, doctor_id=?, "
              + "emergency=?, enq_seq=? WHERE token=?")) {
            ps.setString(1, p.name); ps.setInt(2, p.age); ps.setString(3, p.gender);
            ps.setString(4, p.phone); ps.setString(5, p.complaint); ps.setInt(6, p.doctorId);
            ps.setBoolean(7, p.emergency); ps.setLong(8, p.enqSeq); ps.setInt(9, p.token);
            ps.executeUpdate();
        }
    }

    public synchronized void updateStatus(Patient p) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE patients SET status=?, called_at=?, finished_at=? WHERE token=?")) {
            ps.setString(1, p.status); ps.setTimestamp(2, ts(p.calledAt));
            ps.setTimestamp(3, ts(p.finishedAt)); ps.setInt(4, p.token);
            ps.executeUpdate();
        }
    }

    public synchronized List<Patient> waiting() throws SQLException {
        return query("WHERE status='WAITING' ORDER BY enq_seq");
    }

    public synchronized List<Patient> serving() throws SQLException {
        return query("WHERE status='SERVING' ORDER BY called_at");
    }

    public synchronized List<Patient> search(String q) throws SQLException {
        String like = "%" + q.toLowerCase() + "%";
        return query("WHERE LOWER(name) LIKE ? OR LOWER(phone) LIKE ? OR LOWER(complaint) LIKE ? "
                   + "OR CAST(token AS VARCHAR) = ? ORDER BY token DESC LIMIT 50",
                like, like, like, q.trim());
    }

    public synchronized List<Patient> history(int limit) throws SQLException {
        return query("WHERE status IN ('DONE','CANCELLED') "
                   + "ORDER BY COALESCE(finished_at, registered_at) DESC LIMIT " + limit);
    }

    public synchronized Map<String, Integer> counts() throws SQLException {
        Map<String, Integer> m = new HashMap<>();
        try (Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT status, COUNT(*) FROM patients GROUP BY status")) {
            while (r.next()) m.put(r.getString(1), r.getInt(2));
        }
        return m;
    }

    /** Average waiting time (registration -> called) in minutes, for patients already called. */
    public synchronized double avgWaitMinutes() throws SQLException {
        try (Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT AVG(DATEDIFF('SECOND', registered_at, called_at)) "
                     + "FROM patients WHERE called_at IS NOT NULL")) {
            if (r.next()) { double v = r.getDouble(1); return r.wasNull() ? 0 : v / 60.0; }
        }
        return 0;
    }

    public synchronized long maxSeq() throws SQLException {
        try (Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT COALESCE(MAX(enq_seq), 0) FROM patients")) {
            r.next(); return r.getLong(1);
        }
    }

    // ---------------------------------------------------------- activity log
    public synchronized void log(String op, String detail) {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO ops_log(ts, op, detail) VALUES (?,?,?)")) {
            ps.setTimestamp(1, ts(LocalDateTime.now()));
            ps.setString(2, op);
            ps.setString(3, detail.length() > 300 ? detail.substring(0, 300) : detail);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("log failed: " + e.getMessage());
        }
    }

    public synchronized List<Map<String, Object>> logs(int limit) throws SQLException {
        List<Map<String, Object>> out = new ArrayList<>();
        try (Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT id, ts, op, detail FROM ops_log ORDER BY id DESC LIMIT " + limit)) {
            while (r.next()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", r.getLong(1));
                m.put("time", r.getTimestamp(2).toLocalDateTime().toString());
                m.put("op", r.getString(3));
                m.put("detail", r.getString(4));
                out.add(m);
            }
        }
        return out;
    }

    /** Delete all patients and logs (doctors are kept). */
    public synchronized void resetPatients() throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("TRUNCATE TABLE patients RESTART IDENTITY");
            s.execute("TRUNCATE TABLE ops_log RESTART IDENTITY");
        }
    }

    public synchronized void close() {
        try { c.close(); } catch (SQLException ignored) { }
    }
}
