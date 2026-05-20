package ca.mcgill.mycourses.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Course {
    private long orgUnitId;
    private String name;
    private String code;
    private boolean isActive;

    public Course() {}

    public long getOrgUnitId() { return orgUnitId; }
    public void setOrgUnitId(long orgUnitId) { this.orgUnitId = orgUnitId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public boolean isActive() { return isActive; }
    public void setActive(boolean active) { isActive = active; }

    @Override
    public String toString() {
        return code + " - " + name + " (id=" + orgUnitId + ")";
    }
}
