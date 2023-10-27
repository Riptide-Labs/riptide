package org.riptide.classification;

import java.util.Objects;

import com.google.common.base.Preconditions;
import lombok.Data;

@Data
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
}
