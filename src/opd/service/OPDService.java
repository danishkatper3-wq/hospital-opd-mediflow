package opd.service;

import opd.db.Database;
import opd.ds.ArrayQueue;
import opd.model.Doctor;
import opd.model.Patient;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Business logic of the Hospital OPD: registration, calling patients, editing, cancelling,
 * statistics and history. Every doctor has an emergency waiting line and a regular waiting line.
 */
public class OPDService {

    public static final int NORMAL_CAP = 20;
    public static final int EMERGENCY_CAP = 8;

    private final Database db;
    private final Map<Integer, Desk> desks = new LinkedHashMap<>();
    private long seq;

    /** One doctor, the two waiting lines and the patient currently in the room. */
    private static class Desk {
        final Doctor doctor;
        final ArrayQueue<Patient> emergency = new ArrayQueue<>(EMERGENCY_CAP);
        final ArrayQueue<Patient> normal = new ArrayQueue<>(NORMAL_CAP);
        Patient serving;

        Desk(Doctor d) { this.doctor = d; }

        ArrayQueue<Patient> lane(boolean emergencyLane) { return emergencyLane ? emergency : normal; }
        int waiting() { return emergency.size() + normal.size(); }
    }

    private static class Located {
        Desk desk; ArrayQueue<Patient> lane; Patient patient;
    }

    // ------------------------------------------------------------------ start-up
    public OPDService(Database db) throws SQLException {
        this.db = db;
        List<Doctor> docs = db.doctors();
        if (docs.isEmpty()) {
            db.addDoctor("Dr. Ahmed Raza", "Cardiology", "Room 1", 12);
            db.addDoctor("Dr. Sana Malik", "Pediatrics", "Room 2", 8);
            db.addDoctor("Dr. Imran Qureshi", "Orthopedic", "Room 3", 10);
            db.addDoctor("Dr. Hina Farooq", "General Medicine", "Room 4", 6);
            docs = db.doctors();
        }
        for (Doctor d : docs) desks.put(d.id, new Desk(d));
        seq = db.maxSeq();
        // Restore the waiting patients from the database in their original order
        for (Patient p : db.waiting()) {
            Desk d = desks.get(p.doctorId);
            if (d != null) d.lane(p.emergency).enqueue(p);
        }
        for (Patient p : db.serving()) {
            Desk d = desks.get(p.doctorId);
            if (d != null) d.serving = p;
        }
    }

    // ------------------------------------------------------------ small helpers
    private static String s(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : String.valueOf(v).trim();
    }

    private static Integer i(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v == null || String.valueOf(v).trim().isEmpty()) return null;
        try { return (int) Math.round(Double.parseDouble(String.valueOf(v).trim())); }
        catch (NumberFormatException e) { throw new ApiException(400, "'" + k + "' must be a number."); }
    }

    private static String clean(String x) { return x == null ? null : x.replace(',', ' ').trim(); }

    private static String normGender(String g) {
        if (g == null) return null;
        String x = g.trim().toLowerCase();
        if (x.startsWith("m")) return "Male";
        if (x.startsWith("f")) return "Female";
        if (x.startsWith("o")) return "Other";
        return null;
    }

    private Desk desk(Integer id) {
        Desk d = id == null ? null : desks.get(id);
        if (d == null) throw new ApiException(404, "Doctor not found.");
        return d;
    }

    private List<Patient> toList(ArrayQueue<Patient> q) {
        List<Patient> out = new ArrayList<>();
        q.rotate(p -> { out.add(p); return true; });
        return out;
    }

    /** Finds a waiting patient by token. */
    private Located locate(int token) {
        final Located found = new Located();
        for (Desk d : desks.values()) {
            for (int e = 0; e < 2 && found.patient == null; e++) {
                final ArrayQueue<Patient> lane = d.lane(e == 0);
                lane.rotate(p -> {
                    if (p.token == token) { found.desk = d; found.lane = lane; found.patient = p; }
                    return true;
                });
            }
        }
        if (found.patient == null) throw new ApiException(404, "No waiting patient with token " + token + ".");
        return found;
    }

    private void removeFromLane(ArrayQueue<Patient> lane, int token) {
        lane.rotate(p -> p.token != token);
    }

    private String laneName(boolean emergency) { return emergency ? "Emergency" : "Regular"; }

    private Map<String, Object> patientMap(Patient p, int position, Desk d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("token", p.token);
        m.put("name", p.name);
        m.put("age", p.age);
        m.put("gender", p.gender);
        m.put("phone", p.phone);
        m.put("complaint", p.complaint);
        m.put("doctorId", p.doctorId);
        Desk dd = d != null ? d : desks.get(p.doctorId);
        m.put("doctorName", dd == null ? "" : dd.doctor.name);
        m.put("department", dd == null ? "" : dd.doctor.department);
        m.put("emergency", p.emergency);
        m.put("status", p.status);
        m.put("registeredAt", p.registeredAt == null ? null : p.registeredAt.toString());
        m.put("calledAt", p.calledAt == null ? null : p.calledAt.toString());
        m.put("finishedAt", p.finishedAt == null ? null : p.finishedAt.toString());
        if (position >= 0 && dd != null) {
            m.put("position", position);   // 0 = next to be called
            int ahead = position + (dd.serving != null ? 1 : 0);
            m.put("waitMin", ahead * dd.doctor.avgMinutes);
        }
        return m;
    }

    private Map<String, Object> laneMap(ArrayQueue<Patient> q, Desk d, int offset) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("capacity", q.capacity());
        m.put("size", q.size());
        m.put("front", q.frontIndex());
        m.put("rear", q.rearIndex());
        List<Object> slots = new ArrayList<>();
        for (int k = 0; k < q.capacity(); k++) {
            Patient p = q.slot(k);
            slots.add(p == null ? null : p.token);
        }
        m.put("slots", slots);
        List<Object> list = new ArrayList<>();
        int pos = offset;
        for (Patient p : toList(q)) list.add(patientMap(p, pos++, d));
        m.put("patients", list);
        return m;
    }

    private Patient requirePatientInput(Map<String, Object> b, Patient base) {
        Patient p = base == null ? new Patient() : base;
        String name = clean(s(b, "name"));
        if (base == null || b.containsKey("name")) {
            if (name == null || name.isEmpty()) throw new ApiException(400, "Patient name is required.");
            p.name = name;
        }
        if (base == null || b.containsKey("age")) {
            Integer age = i(b, "age");
            if (age == null || age < 0 || age > 120) throw new ApiException(400, "Age must be between 0 and 120.");
            p.age = age;
        }
        if (base == null || b.containsKey("gender")) {
            String g = normGender(s(b, "gender"));
            if (g == null) throw new ApiException(400, "Gender must be Male, Female or Other.");
            p.gender = g;
        }
        if (base == null || b.containsKey("phone")) {
            String ph = clean(s(b, "phone"));
            p.phone = ph == null ? "" : ph;
        }
        if (base == null || b.containsKey("complaint")) {
            String c = clean(s(b, "complaint"));
            if (c == null || c.isEmpty()) throw new ApiException(400, "Complaint is required.");
            p.complaint = c;
        }
        return p;
    }

    // ================================================================== CREATE
    public synchronized Map<String, Object> register(Map<String, Object> b) {
        try {
            Desk d = desk(i(b, "doctorId"));
            boolean emergency = Boolean.parseBoolean(String.valueOf(b.get("emergency")));
            ArrayQueue<Patient> lane = d.lane(emergency);
            if (lane.isFull())
                throw new ApiException(409, "The " + laneName(emergency).toLowerCase()
                        + " waiting area of " + d.doctor.name + " is full (" + lane.capacity() + " patients).");
            Patient p = requirePatientInput(b, null);
            p.doctorId = d.doctor.id;
            p.emergency = emergency;
            p.status = "WAITING";
            p.enqSeq = ++seq;
            p.registeredAt = LocalDateTime.now();
            p.token = db.insertPatient(p);
            lane.enqueue(p);
            db.log("REGISTER", "Token " + p.token + " (" + p.name + ") registered with " + d.doctor.name
                    + " (" + laneName(emergency) + "), " + lane.size() + " waiting in this group");
            int pos = (emergency ? 0 : d.emergency.size()) + lane.size() - 1;
            return patientMap(p, pos, d);
        } catch (SQLException e) {
            throw new ApiException(500, "Database error: " + e.getMessage());
        }
    }

    public synchronized Map<String, Object> addDoctor(Map<String, Object> b) {
        try {
            String name = clean(s(b, "name")), dept = clean(s(b, "department")), room = clean(s(b, "room"));
            Integer avg = i(b, "avgMinutes");
            if (name == null || name.isEmpty() || dept == null || dept.isEmpty())
                throw new ApiException(400, "Doctor name and department are required.");
            if (avg == null || avg < 1 || avg > 120) avg = 10;
            int id = db.addDoctor(name, dept, room == null ? "" : room, avg);
            Doctor doc = new Doctor(id, name, dept, room == null ? "" : room, avg);
            desks.put(id, new Desk(doc));
            db.log("DOCTOR", "Added " + name + " (" + dept + ")");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id); m.put("name", name);
            return m;
        } catch (SQLException e) {
            throw new ApiException(500, "Database error: " + e.getMessage());
        }
    }

    // ==================================================================== READ
    /** Full snapshot for the dashboard. */
    public synchronized Map<String, Object> state() {
        try {
            Map<String, Object> root = new LinkedHashMap<>();
            List<Object> docs = new ArrayList<>();
            int waiting = 0, emergencyWaiting = 0, serving = 0;
            for (Desk d : desks.values()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", d.doctor.id);
                m.put("name", d.doctor.name);
                m.put("department", d.doctor.department);
                m.put("room", d.doctor.room);
                m.put("avgMinutes", d.doctor.avgMinutes);
                m.put("waiting", d.waiting());
                m.put("serving", d.serving == null ? null : patientMap(d.serving, -1, d));
                m.put("emergency", laneMap(d.emergency, d, 0));
                m.put("normal", laneMap(d.normal, d, d.emergency.size()));
                docs.add(m);
                waiting += d.waiting();
                emergencyWaiting += d.emergency.size();
                if (d.serving != null) serving++;
            }
            Map<String, Integer> cnt = db.counts();
            Map<String, Object> st = new LinkedHashMap<>();
            st.put("waiting", waiting);
            st.put("emergencyWaiting", emergencyWaiting);
            st.put("serving", serving);
            st.put("done", cnt.getOrDefault("DONE", 0));
            st.put("cancelled", cnt.getOrDefault("CANCELLED", 0));
            st.put("totalRegistered", cnt.values().stream().mapToInt(Integer::intValue).sum());
            st.put("avgWaitMin", Math.round(db.avgWaitMinutes() * 10.0) / 10.0);
            st.put("doctors", desks.size());
            root.put("doctors", docs);
            root.put("stats", st);
            root.put("normalCapacity", NORMAL_CAP);
            root.put("emergencyCapacity", EMERGENCY_CAP);
            root.put("serverTime", LocalDateTime.now().toString());
            return root;
        } catch (SQLException e) {
            throw new ApiException(500, "Database error: " + e.getMessage());
        }
    }

    /** Shows the next patient without calling him/her. */
    public synchronized Map<String, Object> nextPatient(int doctorId) {
        Desk d = desk(doctorId);
        Patient p = d.emergency.front();
        if (p == null) p = d.normal.front();
        if (p == null) throw new ApiException(409, "No patients are waiting.");
        db.log("VIEW", "Next patient of " + d.doctor.name + " is Token " + p.token + " (" + p.name + ")");
        return patientMap(p, 0, d);
    }

    public synchronized List<Object> search(String q) {
        try {
            List<Object> out = new ArrayList<>();
            if (q == null || q.trim().isEmpty()) return out;
            for (Patient p : db.search(q.trim())) {
                int pos = -1;
                if ("WAITING".equals(p.status)) pos = positionOf(p);
                out.add(patientMap(p, pos, null));
            }
            return out;
        } catch (SQLException e) {
            throw new ApiException(500, "Database error: " + e.getMessage());
        }
    }

    private int positionOf(Patient target) {
        Desk d = desks.get(target.doctorId);
        if (d == null) return -1;
        List<Patient> em = toList(d.emergency);
        for (int k = 0; k < em.size(); k++) if (em.get(k).token == target.token) return k;
        List<Patient> nm = toList(d.normal);
        for (int k = 0; k < nm.size(); k++) if (nm.get(k).token == target.token) return em.size() + k;
        return -1;
    }

    public synchronized List<Object> history(int limit) {
        try {
            List<Object> out = new ArrayList<>();
            for (Patient p : db.history(limit)) out.add(patientMap(p, -1, null));
            return out;
        } catch (SQLException e) {
            throw new ApiException(500, "Database error: " + e.getMessage());
        }
    }

    public synchronized List<Map<String, Object>> logs(int limit) {
        try { return db.logs(limit); }
        catch (SQLException e) { throw new ApiException(500, "Database error: " + e.getMessage()); }
    }

    public synchronized List<Object> doctors() {
        List<Object> out = new ArrayList<>();
        for (Desk d : desks.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.doctor.id); m.put("name", d.doctor.name);
            m.put("department", d.doctor.department); m.put("room", d.doctor.room);
            m.put("avgMinutes", d.doctor.avgMinutes);
            out.add(m);
        }
        return out;
    }

    // ================================================================== UPDATE
    /** Edits a waiting patient. The position stays the same unless the doctor or the emergency
     *  flag changes (then the patient goes to the end of the new waiting line). */
    public synchronized Map<String, Object> update(int token, Map<String, Object> b) {
        try {
            Located loc = locate(token);
            Patient p = loc.patient;
            requirePatientInput(b, p);
            Integer nd = b.containsKey("doctorId") ? i(b, "doctorId") : null;
            int newDoc = nd == null ? p.doctorId : nd;
            boolean newEm = b.containsKey("emergency")
                    ? Boolean.parseBoolean(String.valueOf(b.get("emergency"))) : p.emergency;
            String note = "details updated";
            if (newDoc != p.doctorId || newEm != p.emergency) {
                Desk target = desk(newDoc);
                ArrayQueue<Patient> targetLane = target.lane(newEm);
                if (targetLane.isFull())
                    throw new ApiException(409, "The selected waiting area is full.");
                removeFromLane(loc.lane, token);
                p.doctorId = target.doctor.id;
                p.emergency = newEm;
                p.enqSeq = ++seq;
                targetLane.enqueue(p);
                note = "moved to " + target.doctor.name + " (" + laneName(newEm) + ") at the end of the line";
                loc.desk = target;
            }
            db.updatePatient(p);
            db.log("UPDATE", "Token " + p.token + " (" + p.name + ") " + note);
            return patientMap(p, positionOf(p), loc.desk);
        } catch (SQLException e) {
            throw new ApiException(500, "Database error: " + e.getMessage());
        }
    }

    // ================================================================== DELETE
    /** Doctor calls the next patient (emergency patients first). */
    public synchronized Map<String, Object> callNext(int doctorId) {
        try {
            Desk d = desk(doctorId);
            Patient previous = d.serving;
            Patient next = d.emergency.dequeue();
            String lane = "Emergency";
            if (next == null) { next = d.normal.dequeue(); lane = "Regular"; }

            if (next == null) {
                if (previous == null)
                    throw new ApiException(409, "No patients are waiting for " + d.doctor.name + ".");
                finish(previous);
                d.serving = null;
                db.log("FINISH", d.doctor.name + " finished with Token " + previous.token + "; nobody else is waiting");
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("message", "Consultation finished. No more patients are waiting.");
                m.put("patient", null);
                return m;
            }
            if (previous != null) finish(previous);
            next.status = "SERVING";
            next.calledAt = LocalDateTime.now();
            d.serving = next;
            db.updateStatus(next);
            db.log("CALL", "Token " + next.token + " (" + next.name + ") called by " + d.doctor.name
                    + " (" + lane + ")");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("message", "Now serving token " + next.token + " - " + next.name);
            m.put("patient", patientMap(next, -1, d));
            return m;
        } catch (SQLException e) {
            throw new ApiException(500, "Database error: " + e.getMessage());
        }
    }

    private void finish(Patient p) throws SQLException {
        p.status = "DONE";
        p.finishedAt = LocalDateTime.now();
        db.updateStatus(p);
    }

    /** Cancels a waiting patient; the others keep their order. */
    public synchronized Map<String, Object> cancel(int token) {
        try {
            Located loc = locate(token);
            Patient p = loc.patient;
            removeFromLane(loc.lane, token);
            p.status = "CANCELLED";
            p.finishedAt = LocalDateTime.now();
            db.updateStatus(p);
            db.log("REMOVE", "Token " + p.token + " (" + p.name + ") cancelled for "
                    + loc.desk.doctor.name + " (" + laneName(p.emergency) + ")");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("message", "Token " + token + " cancelled.");
            return m;
        } catch (SQLException e) {
            throw new ApiException(500, "Database error: " + e.getMessage());
        }
    }

    // ============================================================ demo / reset
    public synchronized Map<String, Object> demo() {
        Object[][] rows = {
            {"Ali Khan", 45, "Male", "Chest pain", 0, false},
            {"Sara Ahmed", 8, "Female", "High fever", 1, false},
            {"Bilal Hussain", 30, "Male", "Knee pain", 2, false},
            {"Ayesha Noor", 27, "Female", "Headache and fatigue", 3, false},
            {"Usman Raza", 52, "Male", "Palpitations", 0, true},
            {"Zain Malik", 6, "Male", "Cough and cold", 1, false},
            {"Fatima Zehra", 61, "Female", "Joint pain", 2, false},
            {"Hamza Iqbal", 19, "Male", "Sports injury", 2, true},
            {"Mariam Baig", 41, "Female", "Blood pressure check", 0, false},
            {"Danish Memon", 29, "Male", "Stomach ache", 3, false},
            {"Noor Fatima", 4, "Female", "Vomiting", 1, false},
            {"Kamran Ali", 38, "Male", "Back pain", 2, false},
        };
        List<Integer> ids = new ArrayList<>(desks.keySet());
        int added = 0;
        for (Object[] r : rows) {
            Map<String, Object> b = new HashMap<>();
            b.put("name", r[0]); b.put("age", r[1]); b.put("gender", r[2]); b.put("complaint", r[3]);
            b.put("doctorId", ids.get(((Integer) r[4]) % ids.size()));
            b.put("emergency", r[5]);
            b.put("phone", "0300-" + (1000000 + (int) (Math.random() * 8999999)));
            try { register(b); added++; } catch (ApiException ignored) { }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("message", added + " demo patients registered.");
        return m;
    }

    public synchronized Map<String, Object> reset() {
        try {
            db.resetPatients();
            for (Desk d : desks.values()) {
                d.emergency.rotate(p -> false);
                d.normal.rotate(p -> false);
                d.serving = null;
            }
            seq = 0;
            db.log("RESET", "All patients and logs cleared");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("message", "All patient data cleared.");
            return m;
        } catch (SQLException e) {
            throw new ApiException(500, "Database error: " + e.getMessage());
        }
    }
}
