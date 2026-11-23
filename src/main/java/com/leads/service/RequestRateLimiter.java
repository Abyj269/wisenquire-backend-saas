package com.leads.service;

import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded local abuse protection; edge rate limits should also be applied across replicas. */
@Service
public class RequestRateLimiter {
    private record Window(long start, int count) {}
    private final ConcurrentHashMap<String,Window> windows=new ConcurrentHashMap<>();
    public void check(String key, int max, long durationMs) {
        long now=System.currentTimeMillis();
        if(windows.size()>10000) windows.entrySet().removeIf(e->now-e.getValue().start()>3600000);
        if(windows.size()>20000 && !windows.containsKey(key)) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Please retry later.");
        windows.compute(key,(k,w)->{
            if(w==null || now-w.start()>=durationMs) return new Window(now,1);
            if(w.count()>=max) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Too many attempts. Please retry later.");
            return new Window(w.start(),w.count()+1);
        });
    }
}
