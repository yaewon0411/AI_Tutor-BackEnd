package org.example.model.dto.openai;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class AssistantModifyRequestDto {
    private String instructions;
    private String model;
    @JsonProperty("file_ids")
    private Object fileIds;
    private String description;

}
