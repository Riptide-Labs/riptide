package org.riptide.repository.elastic.bulk;

import com.google.gson.annotations.SerializedName;
import lombok.Data;

@Data
public class BulkErrorDto {
    private final String type;

    private final String reason;

    @SerializedName("caused_by")
    private final BulkErrorDto cause;
}
