package itkach.aard2.descriptor;

import com.fasterxml.jackson.annotation.JsonProperty;

public abstract class BaseDescriptor {
    @JsonProperty("id")
    public String id;

    @JsonProperty("createdAt")
    public long createdAt;

    @JsonProperty("lastAccess")
    public long lastAccess;
}
