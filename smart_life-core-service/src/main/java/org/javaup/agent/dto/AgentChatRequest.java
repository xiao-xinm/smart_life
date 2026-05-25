package org.javaup.agent.dto;

import lombok.Data;

import java.util.Map;

@Data
public class AgentChatRequest {

    private String message;

    private Map<String, Object> context;
}
