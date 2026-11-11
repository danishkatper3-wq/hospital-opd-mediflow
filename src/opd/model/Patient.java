package opd.model;

import java.time.LocalDateTime;

public class Patient {
    public int token;                 // token number (primary key)
    public String name;
    public int age;
    public String gender;
    public String phone;
    public String complaint;
    public int doctorId;
    public boolean emergency;
    public String status;             // WAITING | SERVING | DONE | CANCELLED
    public long enqSeq;               // arrival order inside the waiting line
    public LocalDateTime registeredAt;
    public LocalDateTime calledAt;
    public LocalDateTime finishedAt;
}
