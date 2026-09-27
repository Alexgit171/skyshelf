package ru.skyshelf;

import java.util.*;
import org.springframework.stereotype.Service;

@Service
class AuditService {
    private final Audits audits;private final Members members;private final Accounts accounts;
    AuditService(Audits a,Members m,Accounts u){audits=a;members=m;accounts=u;}

    void record(String actor,String project,String action,String type,String target,String details) {
        AuditEvent e=new AuditEvent();e.actorId=actor;e.projectId=project;e.action=action;e.targetType=type;e.targetId=target;
        e.details=details==null?"":details.strip().substring(0,Math.min(500,details.strip().length()));audits.save(e);
    }

    List<Map<String,Object>> visible(String user,int requested) {
        int limit=Math.max(1,Math.min(100,requested));Set<String> projectIds=new HashSet<>();members.findByUserId(user).forEach(m->projectIds.add(m.projectId));
        return audits.findAll().stream().filter(e->Objects.equals(e.actorId,user)||(e.projectId!=null&&projectIds.contains(e.projectId)))
            .sorted(Comparator.comparing((AuditEvent e)->e.createdAt).reversed()).limit(limit).map(this::view).toList();
    }

    private Map<String,Object> view(AuditEvent e) {
        Map<String,Object> out=new LinkedHashMap<>();out.put("id",e.id);out.put("action",e.action);out.put("targetType",e.targetType);out.put("targetId",e.targetId);out.put("projectId",e.projectId);out.put("details",e.details);out.put("createdAt",e.createdAt);
        out.put("actor",accounts.findById(e.actorId).map(a->a.name).orElse("Система"));return out;
    }
}
