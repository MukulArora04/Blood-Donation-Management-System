import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BloodDonationDAO {

    // 1. Role-Based Authentication
    public String[] authenticateUser(String email, String password) {
        String query = "SELECT user_id, role, full_name FROM users WHERE email = ? AND password = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(query)) {

            stmt.setString(1, email);
            stmt.setString(2, password);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return new String[]{
                        String.valueOf(rs.getInt("user_id")),
                        rs.getString("role"),
                        rs.getString("full_name")
                    };
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    // 2. Register Donor & Generate Queue Token (Handles new & existing emails)
    public String registerDonorAndGenerateToken(String name, String email, String phone, String bloodGroup, int age, String gender, String city, int unitsPledged) {
        String checkUserSQL = "SELECT user_id FROM users WHERE email = ?";
        String insertUserSQL = "INSERT INTO users (full_name, email, password, role, phone) VALUES (?, ?, 'donor123', 'USER', ?)";
        String checkDonorSQL = "SELECT donor_id FROM donors WHERE user_id = ?";
        String insertDonorSQL = "INSERT INTO donors (user_id, blood_group, age, gender, city) VALUES (?, ?, ?, ?, ?)";
        String countQueueSQL = "SELECT COUNT(*) FROM donor_queue WHERE status = 'WAITING'";
        String insertQueueSQL = "INSERT INTO donor_queue (donor_id, units_offered, ticket_number, queue_position, status) VALUES (?, ?, ?, ?, 'WAITING')";

        Connection conn = null;
        try {
            conn = DBConnection.getConnection();
            conn.setAutoCommit(false);

            int userId = -1;

            // Check if user exists
            try (PreparedStatement checkStmt = conn.prepareStatement(checkUserSQL)) {
                checkStmt.setString(1, email);
                try (ResultSet rs = checkStmt.executeQuery()) {
                    if (rs.next()) {
                        userId = rs.getInt("user_id");
                    }
                }
            }

            // Create user if not exists
            if (userId == -1) {
                try (PreparedStatement userStmt = conn.prepareStatement(insertUserSQL, Statement.RETURN_GENERATED_KEYS)) {
                    userStmt.setString(1, name);
                    userStmt.setString(2, email);
                    userStmt.setString(3, phone);
                    userStmt.executeUpdate();

                    try (ResultSet rs = userStmt.getGeneratedKeys()) {
                        if (rs.next()) userId = rs.getInt(1);
                    }
                }
            }

            if (userId == -1) {
                conn.rollback();
                return null;
            }

            // Check if donor record exists
            int donorId = -1;
            try (PreparedStatement checkDonorStmt = conn.prepareStatement(checkDonorSQL)) {
                checkDonorStmt.setInt(1, userId);
                try (ResultSet rs = checkDonorStmt.executeQuery()) {
                    if (rs.next()) {
                        donorId = rs.getInt("donor_id");
                    }
                }
            }

            // Create donor record if not exists
            if (donorId == -1) {
                try (PreparedStatement donorStmt = conn.prepareStatement(insertDonorSQL, Statement.RETURN_GENERATED_KEYS)) {
                    donorStmt.setInt(1, userId);
                    donorStmt.setString(2, bloodGroup);
                    donorStmt.setInt(3, age);
                    donorStmt.setString(4, gender);
                    donorStmt.setString(5, city);
                    donorStmt.executeUpdate();

                    try (ResultSet rs = donorStmt.getGeneratedKeys()) {
                        if (rs.next()) donorId = rs.getInt(1);
                    }
                }
            }

            if (donorId == -1) {
                conn.rollback();
                return null;
            }

            // Issue Token & Queue Position
            int waitingCount = 0;
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(countQueueSQL)) {
                if (rs.next()) waitingCount = rs.getInt(1);
            }

            int position = waitingCount + 1;
            String ticketNumber = "TK-" + (100 + donorId);

            try (PreparedStatement queueStmt = conn.prepareStatement(insertQueueSQL)) {
                queueStmt.setInt(1, donorId);
                queueStmt.setInt(2, unitsPledged);
                queueStmt.setString(3, ticketNumber);
                queueStmt.setInt(4, position);
                queueStmt.executeUpdate();
            }

            conn.commit();
            return ticketNumber + " (Queue Position: #" + position + ")";
        } catch (SQLException e) {
            System.err.println(">>> SQL ERROR DURING DONOR REGISTRATION: " + e.getMessage());
            if (conn != null) try { conn.rollback(); } catch (SQLException ex) { ex.printStackTrace(); }
            return null;
        } finally {
            if (conn != null) try { conn.setAutoCommit(true); conn.close(); } catch (SQLException e) { e.printStackTrace(); }
        }
    }

    // 3. Fetch Live Queue for Hospital Admin
    public List<Object[]> getLiveDonorQueue() {
        List<Object[]> queue = new ArrayList<>();
        String query = "SELECT q.ticket_id, q.ticket_number, u.full_name, d.blood_group, q.units_offered, q.queue_position, q.status " +
                       "FROM donor_queue q " +
                       "JOIN donors d ON q.donor_id = d.donor_id " +
                       "JOIN users u ON d.user_id = u.user_id " +
                       "WHERE q.status = 'WAITING' ORDER BY q.queue_position ASC";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(query);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                queue.add(new Object[]{
                    rs.getInt("ticket_id"),
                    rs.getString("ticket_number"),
                    rs.getString("full_name"),
                    rs.getString("blood_group"),
                    rs.getInt("units_offered"),
                    "#" + rs.getInt("queue_position"),
                    rs.getString("status")
                });
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return queue;
    }

    // 4. Complete Donation Call & Increment Stock
    public boolean completeDonationCall(int ticketId) {
        String fetchTokenSQL = "SELECT q.donor_id, d.blood_group, q.units_offered FROM donor_queue q JOIN donors d ON q.donor_id = d.donor_id WHERE q.ticket_id = ?";
        String updateTokenSQL = "UPDATE donor_queue SET status = 'COMPLETED' WHERE ticket_id = ?";
        String updateStockSQL = "UPDATE blood_stock SET units_available = units_available + ? WHERE blood_group = ?";
        String reorderQueueSQL = "UPDATE donor_queue SET queue_position = queue_position - 1 WHERE status = 'WAITING'";

        Connection conn = null;
        try {
            conn = DBConnection.getConnection();
            conn.setAutoCommit(false);

            String bloodGroup = "";
            int units = 0;

            try (PreparedStatement stmt = conn.prepareStatement(fetchTokenSQL)) {
                stmt.setInt(1, ticketId);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        bloodGroup = rs.getString("blood_group");
                        units = rs.getInt("units_offered");
                    }
                }
            }

            try (PreparedStatement stmt = conn.prepareStatement(updateTokenSQL)) {
                stmt.setInt(1, ticketId);
                stmt.executeUpdate();
            }

            try (PreparedStatement stmt = conn.prepareStatement(updateStockSQL)) {
                stmt.setInt(1, units);
                stmt.setString(2, bloodGroup);
                stmt.executeUpdate();
            }

            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate(reorderQueueSQL);
            }

            conn.commit();
            return true;
        } catch (SQLException e) {
            if (conn != null) try { conn.rollback(); } catch (SQLException ex) { ex.printStackTrace(); }
            e.printStackTrace();
            return false;
        } finally {
            if (conn != null) try { conn.setAutoCommit(true); conn.close(); } catch (SQLException e) { e.printStackTrace(); }
        }
    }

    // 5. Dashboard Stats
    public Map<String, Integer> getDashboardStats() {
        Map<String, Integer> stats = new HashMap<>();
        stats.put("total_units", 0);
        stats.put("donors_count", 0);
        stats.put("pending_requests", 0);
        stats.put("critical_groups", 0);

        String sqlStock = "SELECT COALESCE(SUM(units_available), 0) AS total, COUNT(CASE WHEN units_available < 4 THEN 1 END) AS critical FROM blood_stock";
        String sqlDonors = "SELECT COUNT(*) AS total FROM donors";
        String sqlReqs = "SELECT COUNT(*) AS total FROM blood_requests WHERE request_status = 'PENDING'";

        try (Connection conn = DBConnection.getConnection()) {
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sqlStock)) {
                if (rs.next()) {
                    stats.put("total_units", rs.getInt("total"));
                    stats.put("critical_groups", rs.getInt("critical"));
                }
            }
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sqlDonors)) {
                if (rs.next()) stats.put("donors_count", rs.getInt("total"));
            }
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sqlReqs)) {
                if (rs.next()) stats.put("pending_requests", rs.getInt("total"));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return stats;
    }

    // 6. Submit Emergency Request
    public boolean submitBloodRequest(String patientName, String bloodGroup, int unitsNeeded, String hospitalName, String city, String contactNumber) {
        String query = "INSERT INTO blood_requests (patient_name, blood_group, units_needed, hospital_name, city, contact_number, request_status) VALUES (?, ?, ?, ?, ?, ?, 'PENDING')";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(query)) {
            stmt.setString(1, patientName);
            stmt.setString(2, bloodGroup);
            stmt.setInt(3, unitsNeeded);
            stmt.setString(4, hospitalName);
            stmt.setString(5, city);
            stmt.setString(6, contactNumber);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    // 7. Get All Requests
    public List<Object[]> getAllRequests() {
        List<Object[]> list = new ArrayList<>();
        String query = "SELECT request_id, patient_name, blood_group, units_needed, hospital_name, city, contact_number, request_status FROM blood_requests ORDER BY request_id DESC";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(query);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                list.add(new Object[]{
                    rs.getInt("request_id"),
                    rs.getString("patient_name"),
                    rs.getString("blood_group"),
                    rs.getInt("units_needed"),
                    rs.getString("hospital_name"),
                    rs.getString("city"),
                    rs.getString("contact_number"),
                    rs.getString("request_status")
                });
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return list;
    }

    // 8. Process Request Action (Approve / Reject with Transaction Locks)
    public String processRequestAction(int requestId, String action) {
        if ("REJECTED".equalsIgnoreCase(action)) {
            String sql = "UPDATE blood_requests SET request_status = 'REJECTED' WHERE request_id = ? AND request_status = 'PENDING'";
            try (Connection conn = DBConnection.getConnection(); PreparedStatement st = conn.prepareStatement(sql)) {
                st.setInt(1, requestId);
                return st.executeUpdate() > 0 ? "SUCCESS" : "Request already processed";
            } catch (SQLException e) {
                return "Database error: " + e.getMessage();
            }
        }

        Connection conn = null;
        try {
            conn = DBConnection.getConnection();
            conn.setAutoCommit(false);

            String fetchSql = "SELECT blood_group, units_needed, request_status FROM blood_requests WHERE request_id = ? FOR UPDATE";
            String bg = "";
            int needed = 0;
            String status = "";

            try (PreparedStatement st = conn.prepareStatement(fetchSql)) {
                st.setInt(1, requestId);
                try (ResultSet rs = st.executeQuery()) {
                    if (rs.next()) {
                        bg = rs.getString("blood_group");
                        needed = rs.getInt("units_needed");
                        status = rs.getString("request_status");
                    } else {
                        conn.rollback();
                        return "Request ID not found";
                    }
                }
            }

            if (!"PENDING".equalsIgnoreCase(status)) {
                conn.rollback();
                return "Request is already " + status;
            }

            String stockSql = "SELECT units_available FROM blood_stock WHERE blood_group = ? FOR UPDATE";
            int available = 0;
            try (PreparedStatement st = conn.prepareStatement(stockSql)) {
                st.setString(1, bg);
                try (ResultSet rs = st.executeQuery()) {
                    if (rs.next()) available = rs.getInt("units_available");
                }
            }

            if (available < needed) {
                conn.rollback();
                return "Insufficient stock! Required: " + needed + " units, Available: " + available + " units.";
            }

            String deductSql = "UPDATE blood_stock SET units_available = units_available - ? WHERE blood_group = ?";
            try (PreparedStatement st = conn.prepareStatement(deductSql)) {
                st.setInt(1, needed);
                st.setString(2, bg);
                st.executeUpdate();
            }

            String approveSql = "UPDATE blood_requests SET request_status = 'APPROVED' WHERE request_id = ?";
            try (PreparedStatement st = conn.prepareStatement(approveSql)) {
                st.setInt(1, requestId);
                st.executeUpdate();
            }

            conn.commit();
            return "SUCCESS";
        } catch (SQLException e) {
            if (conn != null) try { conn.rollback(); } catch (SQLException ex) { ex.printStackTrace(); }
            return "Error: " + e.getMessage();
        } finally {
            if (conn != null) try { conn.setAutoCommit(true); conn.close(); } catch (SQLException e) { e.printStackTrace(); }
        }
    }

    // 9. Live Blood Stock Overview
    public List<Object[]> getAllBloodStock() {
        List<Object[]> stockList = new ArrayList<>();
        String query = "SELECT blood_group, units_available, last_updated FROM blood_stock ORDER BY blood_group ASC";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(query);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                String bg = rs.getString("blood_group");
                int units = rs.getInt("units_available");
                String status = (units >= 10) ? "Optimal" : (units >= 4 ? "Moderate" : "CRITICAL LOW");
                Timestamp updated = rs.getTimestamp("last_updated");

                stockList.add(new Object[]{bg, units, status, updated != null ? updated.toString() : "N/A"});
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return stockList;
    }

    // 10. Manual Stock Adjustment
    public boolean updateStockManual(String bloodGroup, int newUnits) {
        String sql = "UPDATE blood_stock SET units_available = ? WHERE blood_group = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, newUnits);
            stmt.setString(2, bloodGroup);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }
}