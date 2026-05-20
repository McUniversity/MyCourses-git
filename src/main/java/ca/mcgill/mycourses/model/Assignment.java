package ca.mcgill.mycourses.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Assignment {
    private long id;
    private String name;
    private String instructions;
    private Instant dueDate;
    private float totalPoints;

    public Assignment() {}

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getInstructions() { return instructions; }
    public void setInstructions(String instructions) { this.instructions = instructions; }
    public Instant getDueDate() { return dueDate; }
    public void setDueDate(Instant dueDate) { this.dueDate = dueDate; }
    public float getTotalPoints() { return totalPoints; }
    public void setTotalPoints(float totalPoints) { this.totalPoints = totalPoints; }
}
