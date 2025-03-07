package io.hyperfoil.tools.horreum.api.alerting;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

public class ChangeDetection {
    @JsonProperty( required = true )
    public Integer id;
    @JsonProperty( required = true )
    public String model;
    @NotNull
    @JsonProperty( required = true )
    public JsonNode config;

    public ChangeDetection() {
    }

    public ChangeDetection(Integer id, String model, JsonNode config) {
        this.id = id;
        this.model = model;
        this.config = config;
    }

    public String toString() {
        return "ChangeDetection{id=" + this.id + ", model='" + this.model + '\'' + ", config=" + this.config + '}';
    }
}
