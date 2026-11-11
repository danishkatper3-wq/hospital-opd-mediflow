package opd.model;

public class Doctor {
    public int id;
    public String name;
    public String department;
    public String room;
    public int avgMinutes;      // average consultation time (used for wait estimate)

    public Doctor(int id, String name, String department, String room, int avgMinutes) {
        this.id = id; this.name = name; this.department = department;
        this.room = room; this.avgMinutes = avgMinutes;
    }
}
