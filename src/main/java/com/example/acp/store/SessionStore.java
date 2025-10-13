package com.example.acp.store;

import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SessionStore {
    private final Map<String, Map<String, Object>> sessions = new ConcurrentHashMap<>();
    public Map<String, Object> get(String id) { return sessions.get(id); }
    public void put(String id, Map<String, Object> session) { sessions.put(id, session); }
    public boolean exists(String id) { return sessions.containsKey(id); }
    public void remove(String id) { sessions.remove(id); }
}
