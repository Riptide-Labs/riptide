package org.riptide.classification;

import java.util.Objects;

import com.google.common.base.Preconditions;

public class Protocol {
    private int decimal;
    private String keyword;
    private String description;

    public Protocol(int decimal, String keyword, String description) {
        Objects.requireNonNull(keyword);
        Preconditions.checkArgument(decimal >= 0, "decimal must be >= 0");
        this.decimal = decimal;
        this.keyword = keyword;
        this.description = description;
    }

    public int getDecimal() {
        return decimal;
    }

    public void setDecimal(int decimal) {
        this.decimal = decimal;
    }

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Protocol protocol = (Protocol) o;
        return Objects.equals(decimal, protocol.decimal)
                && Objects.equals(keyword, protocol.keyword)
                && Objects.equals(description, protocol.description);
    }

    @Override
    public int hashCode() {
        return Objects.hash(decimal, keyword, description);
    }

    @Override
    public String toString() {
        return "Protocol{" +
               "decimal=" + decimal +
               ", keyword='" + keyword + '\'' +
               '}';
    }
}
