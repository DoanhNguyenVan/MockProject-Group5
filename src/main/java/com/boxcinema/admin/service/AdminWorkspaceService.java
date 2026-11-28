package com.boxcinema.admin.service;

import java.util.List;
import java.util.Map;

/**
 * Business service interface for Admin Workspace data aggregation, CRUD, impact checking, and facility decommission.
 */
public interface AdminWorkspaceService {

    Map<String, Object> getAdminWorkspaceData();

    Map<String, Object> checkDeactivationImpact(String section, Long id);

    Map<String, Object> resolveDeactivation(String section, Long id, String strategy);

    Map<String, Object> saveRecord(String section, Map<String, Object> body);

    Map<String, Object> deleteRecord(String section, Long id);

    default Map<String, Object> deleteRecord(String section, String id) {
        return deleteRecord(section, Long.valueOf(id));
    }

    Map<String, Object> saveRoomSeats(Long roomId, List<Map<String, Object>> seats);
}
