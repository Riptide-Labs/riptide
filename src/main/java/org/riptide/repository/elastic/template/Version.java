package org.riptide.repository.elastic.template;

import java.util.Objects;

public class Version {
    private int major;
    private int minor;
    private int patch;

    public Version(int major, int minor, int patch) {
        if (major < 0 || minor < 0 || patch < 0) {
            throw new IllegalArgumentException("Versions must be non-negative.");
        }
        this.major = major;
        this.minor = minor;
        this.patch = patch;
    }

    public static Version fromVersionString(String versionString) {
        String[] tokens = versionString.split("\\.");
        if (tokens.length != 3) {
            throw new IllegalArgumentException("Invalid version string: " + versionString);
        }
        return new Version(Integer.parseInt(tokens[0]), Integer.parseInt(tokens[1]), Integer.parseInt(tokens[2]));
    }

    public int getMajor() {
        return major;
    }

    public void setMajor(int major) {
        this.major = major;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Version version = (Version) o;
        return Objects.equals(major, version.major)
                && Objects.equals(minor, version.minor)
                && Objects.equals(patch, version.patch);
    }

    @Override
    public int hashCode() {
        return Objects.hash(major, minor, patch);
    }

    @Override
    public String toString() {
        return String.format("%d.%d.%d", major, minor, patch);
    }
}
