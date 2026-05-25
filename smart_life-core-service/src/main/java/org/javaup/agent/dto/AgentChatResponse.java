package org.javaup.agent.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AgentChatResponse {

    private String answer;

    private List<String> suggestions = new ArrayList<>();

    private List<AgentRecommendation> recommendations = new ArrayList<>();

    private List<String> toolTrace = new ArrayList<>();
}
