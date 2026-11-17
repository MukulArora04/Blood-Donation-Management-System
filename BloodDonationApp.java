import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.JTableHeader;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Map;

public class BloodDonationApp extends JFrame {
    private CardLayout cardLayout;
    private JPanel mainPanel;
    private JTabbedPane tabs;
    private JPanel kpiPanel;
    private BloodDonationDAO dao;

    // Session State
    private String currentUserId = "";
    private String currentUserRole = "";
    private String currentUserName = "";

    // Theme Colors
    private final Color PRIMARY_RED = new Color(185, 28, 28);
    private final Color ADMIN_BLUE = new Color(30, 58, 138);
    private final Color BG_COLOR = new Color(248, 250, 252);
    private final Color CARD_BG = Color.WHITE;
    private final Color CARD_HOVER_BG = new Color(241, 245, 249);
    private final Color TEXT_DARK = new Color(15, 23, 42);

    // KPI Labels
    private JLabel totalUnitsVal, donorsVal, pendingReqVal, criticalVal;

    // Role-Restricted Action Buttons
    private JButton manualEditBtn;
    private JButton callNextBtn;

    // Tables
    private JTable stockTable, reqTable, queueTable;
    private DefaultTableModel stockTableModel, reqTableModel, queueTableModel;

    // Pre-built Tab Panels
    private JPanel stockTabPanel, donorTabPanel, queueTabPanel, requestTabPanel, manageReqTabPanel;

    public BloodDonationApp() {
        dao = new BloodDonationDAO();

        setTitle("Blood Donation & Inventory Management System");
        setSize(1100, 720);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        cardLayout = new CardLayout();
        mainPanel = new JPanel(cardLayout);

        mainPanel.add(createLoginPanel(), "LOGIN");
        mainPanel.add(createDashboardPanel(), "DASHBOARD");

        add(mainPanel);
    }

    // ==========================================
    // 1. LOGIN SCREEN
    // ==========================================
    private JPanel createLoginPanel() {
        JPanel container = new JPanel(new GridBagLayout());
        container.setBackground(BG_COLOR);

        JPanel card = new JPanel(new GridBagLayout());
        card.setBackground(CARD_BG);
        card.setBorder(BorderFactory.createCompoundBorder(
                new LineBorder(new Color(226, 232, 240), 1, true),
                new EmptyBorder(35, 45, 35, 45)
        ));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(8, 8, 8, 8);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        JLabel badge = new JLabel("BLOOD BANK SYSTEM", JLabel.CENTER);
        badge.setForeground(PRIMARY_RED);
        badge.setFont(new Font("Segoe UI", Font.BOLD, 12));

        JLabel title = new JLabel("Portal Login", JLabel.CENTER);
        title.setFont(new Font("Segoe UI", Font.BOLD, 22));
        title.setForeground(TEXT_DARK);

        JLabel sub = new JLabel("Select your role to sign in", JLabel.CENTER);
        sub.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        sub.setForeground(Color.GRAY);

        JTextField emailField = new JTextField("admin@bloodbank.org", 18);
        emailField.setPreferredSize(new Dimension(220, 32));
        JPasswordField passField = new JPasswordField("admin123", 18);
        passField.setPreferredSize(new Dimension(220, 32));

        JButton userLoginBtn = createStyledButton("Sign In as User", PRIMARY_RED);
        JButton adminLoginBtn = createStyledButton("Sign In as Hospital Admin", ADMIN_BLUE);

        userLoginBtn.addActionListener(e -> attemptLogin(emailField.getText().trim(), new String(passField.getPassword()).trim(), "USER"));
        adminLoginBtn.addActionListener(e -> attemptLogin(emailField.getText().trim(), new String(passField.getPassword()).trim(), "ADMIN"));

        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 2; card.add(badge, gbc);
        gbc.gridy = 1; card.add(title, gbc);
        gbc.gridy = 2; card.add(sub, gbc);

        gbc.gridwidth = 1;
        gbc.gridy = 3; gbc.gridx = 0; card.add(new JLabel("Email Address:"), gbc);
        gbc.gridx = 1; card.add(emailField, gbc);

        gbc.gridy = 4; gbc.gridx = 0; card.add(new JLabel("Password:"), gbc);
        gbc.gridx = 1; card.add(passField, gbc);

        gbc.gridy = 5; gbc.gridx = 0; gbc.gridwidth = 2; card.add(userLoginBtn, gbc);
        gbc.gridy = 6; gbc.gridx = 0; gbc.gridwidth = 2; card.add(adminLoginBtn, gbc);

        container.add(card);
        return container;
    }

    private void attemptLogin(String email, String pass, String expectedRole) {
        if (email.isEmpty() || pass.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please enter both email and password.", "Validation Error", JOptionPane.WARNING_MESSAGE);
            return;
        }

        String[] session = dao.authenticateUser(email, pass);
        if (session != null) {
            String dbRole = session[1];

            if ("ADMIN".equalsIgnoreCase(expectedRole) && !"ADMIN".equalsIgnoreCase(dbRole)) {
                JOptionPane.showMessageDialog(this, "Access Denied! You do not have Hospital Admin privileges.", "Role Mismatch", JOptionPane.ERROR_MESSAGE);
                return;
            }

            currentUserId = session[0];
            // Enforce the selected portal view mode (USER mode vs ADMIN mode)
            currentUserRole = expectedRole.toUpperCase();
            currentUserName = session[2];

            configureRoleAccess();
            refreshAllData();
            cardLayout.show(mainPanel, "DASHBOARD");
        } else {
            JOptionPane.showMessageDialog(this, "Invalid credentials. Please verify your email and password.", "Login Failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    // Configure exact tabs, KPI cards, and buttons based on role
    private void configureRoleAccess() {
        boolean isAdmin = "ADMIN".equalsIgnoreCase(currentUserRole);

        // 1. Show/Hide the 4 KPI summary cards at the top
        if (kpiPanel != null) {
            kpiPanel.setVisible(isAdmin);
        }

        // 2. Show/Hide Admin-exclusive action buttons
        if (manualEditBtn != null) {
            manualEditBtn.setVisible(isAdmin);
        }
        if (callNextBtn != null) {
            callNextBtn.setVisible(isAdmin);
        }

        // 3. Rebuild JTabbedPane strictly with allowed tabs for each role
        if (tabs != null) {
            tabs.removeAll();
            if (isAdmin) {
                tabs.addTab("Inventory Overview", stockTabPanel);
                tabs.addTab("Live Donor Queue", queueTabPanel);
                tabs.addTab("Manage Requests", manageReqTabPanel);
            } else {
                tabs.addTab("Register Donor", donorTabPanel);
                tabs.addTab("Live Donor Queue", queueTabPanel);
                tabs.addTab("Request Blood", requestTabPanel);
            }
            tabs.setSelectedIndex(0);
        }
    }

    // ==========================================
    // 2. MAIN DASHBOARD PANEL
    // ==========================================
    private JPanel createDashboardPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(BG_COLOR);

        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(PRIMARY_RED);
        header.setBorder(new EmptyBorder(14, 20, 14, 20));

        JLabel logo = new JLabel("Blood Donation Portal");
        logo.setForeground(Color.WHITE);
        logo.setFont(new Font("Segoe UI", Font.BOLD, 18));

        JButton logoutBtn = createStyledButton("Logout", new Color(127, 29, 29));
        logoutBtn.addActionListener(e -> cardLayout.show(mainPanel, "LOGIN"));

        header.add(logo, BorderLayout.WEST);
        header.add(logoutBtn, BorderLayout.EAST);

        // Initialize all tab panels once
        stockTabPanel = createStockTab();
        donorTabPanel = createDonorTab();
        queueTabPanel = createDonorQueueTab();
        requestTabPanel = createBloodRequestTab();
        manageReqTabPanel = createManageRequestsTab();

        tabs = new JTabbedPane();
        tabs.setFont(new Font("Segoe UI", Font.BOLD, 13));
        tabs.addChangeListener(e -> {
            if (tabs.getTabCount() > 0) {
                refreshAllData();
            }
        });

        kpiPanel = createKPICardsPanel();

        JPanel topContainer = new JPanel(new BorderLayout());
        topContainer.setBackground(BG_COLOR);
        topContainer.add(header, BorderLayout.NORTH);
        topContainer.add(kpiPanel, BorderLayout.CENTER);

        panel.add(topContainer, BorderLayout.NORTH);
        panel.add(tabs, BorderLayout.CENTER);

        return panel;
    }

    private JPanel createKPICardsPanel() {
        JPanel panel = new JPanel(new GridLayout(1, 4, 15, 0));
        panel.setBackground(BG_COLOR);
        panel.setBorder(new EmptyBorder(12, 20, 12, 20));

        totalUnitsVal = new JLabel("0", JLabel.CENTER);
        donorsVal = new JLabel("0", JLabel.CENTER);
        pendingReqVal = new JLabel("0", JLabel.CENTER);
        criticalVal = new JLabel("0", JLabel.CENTER);

        // Map to Admin Tab Indices: 0 = Inventory Overview, 1 = Live Donor Queue, 2 = Manage Requests
        panel.add(buildClickableCard("Total Blood Units", totalUnitsVal, new Color(37, 99, 235), 0));
        panel.add(buildClickableCard("Registered Donors", donorsVal, new Color(16, 185, 129), 1));
        panel.add(buildClickableCard("Pending Requests", pendingReqVal, new Color(245, 158, 11), 2));
        panel.add(buildClickableCard("Critical Shortages", criticalVal, new Color(220, 38, 38), 0));

        return panel;
    }

    private JPanel buildClickableCard(String title, JLabel valueLabel, Color accent, int targetTabIndex) {
        JPanel card = new JPanel(new BorderLayout(5, 5));
        card.setBackground(CARD_BG);
        card.setBorder(BorderFactory.createCompoundBorder(
                new LineBorder(new Color(226, 232, 240), 1, true),
                new EmptyBorder(10, 15, 10, 15)
        ));
        card.setCursor(new Cursor(Cursor.HAND_CURSOR));

        JLabel titleLbl = new JLabel(title, JLabel.CENTER);
        titleLbl.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        titleLbl.setForeground(Color.GRAY);

        valueLabel.setFont(new Font("Segoe UI", Font.BOLD, 22));
        valueLabel.setForeground(accent);

        JLabel clickHint = new JLabel("Click to view details ➔", JLabel.CENTER);
        clickHint.setFont(new Font("Segoe UI", Font.ITALIC, 10));
        clickHint.setForeground(new Color(148, 163, 184));

        card.add(titleLbl, BorderLayout.NORTH);
        card.add(valueLabel, BorderLayout.CENTER);
        card.add(clickHint, BorderLayout.SOUTH);

        card.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (tabs != null && targetTabIndex < tabs.getTabCount()) {
                    tabs.setSelectedIndex(targetTabIndex);
                }
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                card.setBackground(CARD_HOVER_BG);
                card.setBorder(BorderFactory.createCompoundBorder(new LineBorder(accent, 1, true), new EmptyBorder(10, 15, 10, 15)));
            }

            @Override
            public void mouseExited(MouseEvent e) {
                card.setBackground(CARD_BG);
                card.setBorder(BorderFactory.createCompoundBorder(new LineBorder(new Color(226, 232, 240), 1, true), new EmptyBorder(10, 15, 10, 15)));
            }
        });

        return card;
    }

    // ==========================================
    // 3. TAB: STOCK INVENTORY VIEW (ADMIN)
    // ==========================================
    private JPanel createStockTab() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBackground(BG_COLOR);
        panel.setBorder(new EmptyBorder(15, 20, 15, 20));

        String[] cols = {"Blood Group", "Units Available", "Stock Health", "Last Updated"};
        stockTableModel = new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        stockTable = new JTable(stockTableModel);
        styleTable(stockTable);

        JPanel buttonBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 5));
        buttonBar.setBackground(BG_COLOR);

        manualEditBtn = createStyledButton("Manually Adjust Stock Count", new Color(37, 99, 235));
        JButton refreshBtn = createStyledButton("Refresh Stock", PRIMARY_RED);

        manualEditBtn.addActionListener(e -> {
            if (!"ADMIN".equalsIgnoreCase(currentUserRole)) return;
            int row = stockTable.getSelectedRow();
            if (row == -1) {
                JOptionPane.showMessageDialog(this, "Please select a blood group row from the table first.", "Selection Needed", JOptionPane.WARNING_MESSAGE);
                return;
            }
            String bg = (String) stockTableModel.getValueAt(row, 0);
            String input = JOptionPane.showInputDialog(this, "Enter new unit count for " + bg + ":");
            if (input != null && !input.trim().isEmpty()) {
                try {
                    int units = Integer.parseInt(input.trim());
                    if (units < 0) {
                        JOptionPane.showMessageDialog(this, "Units cannot be negative.", "Validation Error", JOptionPane.ERROR_MESSAGE);
                        return;
                    }
                    dao.updateStockManual(bg, units);
                    refreshAllData();
                } catch (NumberFormatException ex) {
                    JOptionPane.showMessageDialog(this, "Please enter a valid numeric value.", "Validation Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        });

        refreshBtn.addActionListener(e -> refreshAllData());

        buttonBar.add(manualEditBtn);
        buttonBar.add(refreshBtn);

        panel.add(new JScrollPane(stockTable), BorderLayout.CENTER);
        panel.add(buttonBar, BorderLayout.SOUTH);
        return panel;
    }

    // ==========================================
    // 4. TAB: REGISTER DONOR (USER)
    // ==========================================
    private JPanel createDonorTab() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBackground(BG_COLOR);

        JPanel card = new JPanel(new GridBagLayout());
        card.setBackground(CARD_BG);
        card.setBorder(BorderFactory.createCompoundBorder(
                new LineBorder(new Color(226, 232, 240), 1, true),
                new EmptyBorder(25, 35, 25, 35)
        ));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 6, 6, 6);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        JTextField nameField = new JTextField(16);
        JTextField emailField = new JTextField(16);
        JTextField phoneField = new JTextField(16);
        JTextField ageField = new JTextField(16);
        JComboBox<String> genderCombo = new JComboBox<>(new String[]{"Male", "Female", "Other"});
        JComboBox<String> bloodCombo = new JComboBox<>(new String[]{"A+", "A-", "B+", "B-", "AB+", "AB-", "O+", "O-"});
        JComboBox<Integer> unitsCombo = new JComboBox<>(new Integer[]{1, 2});
        JTextField cityField = new JTextField(16);

        JButton registerBtn = createStyledButton("Complete Donor Registration & Generate Ticket", PRIMARY_RED);

        registerBtn.addActionListener(e -> {
            try {
                String name = nameField.getText().trim();
                String email = emailField.getText().trim();
                String phone = phoneField.getText().trim();
                String city = cityField.getText().trim();
                String ageStr = ageField.getText().trim();
                int units = (Integer) unitsCombo.getSelectedItem();

                if (name.isEmpty() || email.isEmpty() || phone.isEmpty() || city.isEmpty() || ageStr.isEmpty()) {
                    JOptionPane.showMessageDialog(this, "Please fill in all details.", "Validation Warning", JOptionPane.WARNING_MESSAGE);
                    return;
                }

                int age = Integer.parseInt(ageStr);
                if (age < 18 || age > 65) {
                    JOptionPane.showMessageDialog(this, "Donor age must be between 18 and 65 for safety guidelines.", "Eligibility Warning", JOptionPane.WARNING_MESSAGE);
                    return;
                }

                String tokenResult = dao.registerDonorAndGenerateToken(name, email, phone, (String) bloodCombo.getSelectedItem(), age, (String) genderCombo.getSelectedItem(), city, units);
                if (tokenResult != null) {
                    JOptionPane.showMessageDialog(this, "Registration Successful!\n\nYour Donor Ticket Number: " + tokenResult, "Ticket Issued", JOptionPane.INFORMATION_MESSAGE);
                    nameField.setText(""); emailField.setText(""); phoneField.setText(""); ageField.setText(""); cityField.setText("");
                    refreshAllData();
                    // Switch User directly to Live Donor Queue tab (Index 1 in User View)
                    if (!"ADMIN".equalsIgnoreCase(currentUserRole) && tabs.getTabCount() > 1) {
                        tabs.setSelectedIndex(1);
                    }
                } else {
                    JOptionPane.showMessageDialog(this, "Could not register donor. Ensure database configuration is correct.", "Error", JOptionPane.ERROR_MESSAGE);
                }
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(this, "Age must be a numeric integer.", "Validation Error", JOptionPane.ERROR_MESSAGE);
            }
        });

        gbc.gridx = 0; gbc.gridy = 0; card.add(new JLabel("Full Name:"), gbc);
        gbc.gridx = 1; card.add(nameField, gbc);
        gbc.gridx = 0; gbc.gridy = 1; card.add(new JLabel("Email Address:"), gbc);
        gbc.gridx = 1; card.add(emailField, gbc);
        gbc.gridx = 0; gbc.gridy = 2; card.add(new JLabel("Phone Number:"), gbc);
        gbc.gridx = 1; card.add(phoneField, gbc);
        gbc.gridx = 0; gbc.gridy = 3; card.add(new JLabel("Donor Age:"), gbc);
        gbc.gridx = 1; card.add(ageField, gbc);
        gbc.gridx = 0; gbc.gridy = 4; card.add(new JLabel("Gender:"), gbc);
        gbc.gridx = 1; card.add(genderCombo, gbc);
        gbc.gridx = 0; gbc.gridy = 5; card.add(new JLabel("Blood Group:"), gbc);
        gbc.gridx = 1; card.add(bloodCombo, gbc);
        gbc.gridx = 0; gbc.gridy = 6; card.add(new JLabel("Units to Donate:"), gbc);
        gbc.gridx = 1; card.add(unitsCombo, gbc);
        gbc.gridx = 0; gbc.gridy = 7; card.add(new JLabel("City:"), gbc);
        gbc.gridx = 1; card.add(cityField, gbc);
        gbc.gridx = 0; gbc.gridy = 8; gbc.gridwidth = 2; card.add(registerBtn, gbc);

        panel.add(card);
        return panel;
    }

    // ==========================================
    // 5. TAB: LIVE DONOR QUEUE (USER VIEW-ONLY / ADMIN ACTION)
    // ==========================================
    private JPanel createDonorQueueTab() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBackground(BG_COLOR);
        panel.setBorder(new EmptyBorder(15, 20, 15, 20));

        String[] cols = {"Ticket ID", "Token Number", "Donor Name", "Blood Group", "Units Offered", "Queue Position", "Status"};
        queueTableModel = new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        queueTable = new JTable(queueTableModel);
        styleTable(queueTable);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 5));
        actions.setBackground(BG_COLOR);

        callNextBtn = createStyledButton("Call & Complete Selected Donation", new Color(16, 185, 129));
        JButton refreshQueueBtn = createStyledButton("Refresh Queue", PRIMARY_RED);

        callNextBtn.addActionListener(e -> {
            if (!"ADMIN".equalsIgnoreCase(currentUserRole)) return;
            int row = queueTable.getSelectedRow();
            if (row == -1) {
                JOptionPane.showMessageDialog(this, "Please select a donor ticket row to process.", "Selection Needed", JOptionPane.WARNING_MESSAGE);
                return;
            }
            int ticketId = (int) queueTableModel.getValueAt(row, 0);
            String tokenNum = (String) queueTableModel.getValueAt(row, 1);

            boolean success = dao.completeDonationCall(ticketId);
            if (success) {
                JOptionPane.showMessageDialog(this, "Donation completed for Ticket " + tokenNum + "! Inventory successfully incremented.", "Success", JOptionPane.INFORMATION_MESSAGE);
                refreshAllData();
            } else {
                JOptionPane.showMessageDialog(this, "Could not process donation call.", "Error", JOptionPane.ERROR_MESSAGE);
            }
        });

        refreshQueueBtn.addActionListener(e -> refreshAllData());

        actions.add(callNextBtn);
        actions.add(refreshQueueBtn);

        panel.add(new JScrollPane(queueTable), BorderLayout.CENTER);
        panel.add(actions, BorderLayout.SOUTH);
        return panel;
    }

    // ==========================================
    // 6. TAB: REQUEST BLOOD (USER)
    // ==========================================
    private JPanel createBloodRequestTab() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBackground(BG_COLOR);

        JPanel card = new JPanel(new GridBagLayout());
        card.setBackground(CARD_BG);
        card.setBorder(BorderFactory.createCompoundBorder(
                new LineBorder(new Color(226, 232, 240), 1, true),
                new EmptyBorder(25, 35, 25, 35)
        ));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 6, 6, 6);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        JTextField patientName = new JTextField(16);
        JComboBox<String> bloodCombo = new JComboBox<>(new String[]{"A+", "A-", "B+", "B-", "AB+", "AB-", "O+", "O-"});
        JTextField unitsNeeded = new JTextField(16);
        JTextField hospitalField = new JTextField(16);
        JTextField cityField = new JTextField(16);
        JTextField phoneField = new JTextField(16);

        JButton submitBtn = createStyledButton("Submit Emergency Request", PRIMARY_RED);

        submitBtn.addActionListener(e -> {
            try {
                String patient = patientName.getText().trim();
                String unitsStr = unitsNeeded.getText().trim();
                String hospital = hospitalField.getText().trim();
                String city = cityField.getText().trim();
                String phone = phoneField.getText().trim();

                if (patient.isEmpty() || unitsStr.isEmpty() || hospital.isEmpty() || city.isEmpty() || phone.isEmpty()) {
                    JOptionPane.showMessageDialog(this, "Please fill in all fields.", "Validation Warning", JOptionPane.WARNING_MESSAGE);
                    return;
                }

                int units = Integer.parseInt(unitsStr);
                if (units <= 0) {
                    JOptionPane.showMessageDialog(this, "Units needed must be at least 1.", "Validation Error", JOptionPane.ERROR_MESSAGE);
                    return;
                }

                boolean ok = dao.submitBloodRequest(patient, (String) bloodCombo.getSelectedItem(), units, hospital, city, phone);
                if (ok) {
                    JOptionPane.showMessageDialog(this, "Blood request submitted for Hospital Admin review!");
                    patientName.setText(""); unitsNeeded.setText(""); hospitalField.setText(""); cityField.setText(""); phoneField.setText("");
                    refreshAllData();
                } else {
                    JOptionPane.showMessageDialog(this, "Failed to submit request.", "Error", JOptionPane.ERROR_MESSAGE);
                }
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(this, "Units requested must be a numeric integer.", "Validation Error", JOptionPane.ERROR_MESSAGE);
            }
        });

        gbc.gridx = 0; gbc.gridy = 0; card.add(new JLabel("Patient Name:"), gbc);
        gbc.gridx = 1; card.add(patientName, gbc);
        gbc.gridx = 0; gbc.gridy = 1; card.add(new JLabel("Blood Group:"), gbc);
        gbc.gridx = 1; card.add(bloodCombo, gbc);
        gbc.gridx = 0; gbc.gridy = 2; card.add(new JLabel("Units Needed:"), gbc);
        gbc.gridx = 1; card.add(unitsNeeded, gbc);
        gbc.gridx = 0; gbc.gridy = 3; card.add(new JLabel("Hospital Name:"), gbc);
        gbc.gridx = 1; card.add(hospitalField, gbc);
        gbc.gridx = 0; gbc.gridy = 4; card.add(new JLabel("City:"), gbc);
        gbc.gridx = 1; card.add(cityField, gbc);
        gbc.gridx = 0; gbc.gridy = 5; card.add(new JLabel("Contact Phone:"), gbc);
        gbc.gridx = 1; card.add(phoneField, gbc);
        gbc.gridx = 0; gbc.gridy = 6; gbc.gridwidth = 2; card.add(submitBtn, gbc);

        panel.add(card);
        return panel;
    }

    // ==========================================
    // 7. TAB: MANAGE REQUESTS (ADMIN)
    // ==========================================
    private JPanel createManageRequestsTab() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBackground(BG_COLOR);
        panel.setBorder(new EmptyBorder(15, 20, 15, 20));

        String[] cols = {"Req ID", "Patient Name", "Group", "Units", "Hospital", "City", "Phone", "Status"};
        reqTableModel = new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        reqTable = new JTable(reqTableModel);
        styleTable(reqTable);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 5));
        actions.setBackground(BG_COLOR);

        JButton approveBtn = createStyledButton("Approve Request", new Color(16, 185, 129));
        JButton rejectBtn = createStyledButton("Reject Request", new Color(220, 38, 38));

        approveBtn.addActionListener(e -> handleRequestAction("APPROVED"));
        rejectBtn.addActionListener(e -> handleRequestAction("REJECTED"));

        actions.add(new JLabel("Select a row and choose action: "));
        actions.add(approveBtn);
        actions.add(rejectBtn);

        panel.add(new JScrollPane(reqTable), BorderLayout.CENTER);
        panel.add(actions, BorderLayout.SOUTH);
        return panel;
    }

    private void handleRequestAction(String action) {
        if (!"ADMIN".equalsIgnoreCase(currentUserRole)) return;
        int row = reqTable.getSelectedRow();
        if (row == -1) {
            JOptionPane.showMessageDialog(this, "Please select a request row from the table first.", "Selection Needed", JOptionPane.WARNING_MESSAGE);
            return;
        }

        int reqId = (int) reqTableModel.getValueAt(row, 0);
        String status = (String) reqTableModel.getValueAt(row, 7);

        if (!"PENDING".equalsIgnoreCase(status)) {
            JOptionPane.showMessageDialog(this, "Only PENDING requests can be modified.", "Invalid Operation", JOptionPane.WARNING_MESSAGE);
            return;
        }

        String result = dao.processRequestAction(reqId, action);
        if ("SUCCESS".equalsIgnoreCase(result)) {
            JOptionPane.showMessageDialog(this, "Request #" + reqId + " marked as " + action + "!");
            refreshAllData();
        } else {
            JOptionPane.showMessageDialog(this, result, "Action Notice", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void refreshAllData() {
        Map<String, Integer> stats = dao.getDashboardStats();
        if (totalUnitsVal != null) totalUnitsVal.setText(String.valueOf(stats.get("total_units")));
        if (donorsVal != null) donorsVal.setText(String.valueOf(stats.get("donors_count")));
        if (pendingReqVal != null) pendingReqVal.setText(String.valueOf(stats.get("pending_requests")));
        if (criticalVal != null) criticalVal.setText(String.valueOf(stats.get("critical_groups")));

        if (stockTableModel != null) {
            stockTableModel.setRowCount(0);
            List<Object[]> rows = dao.getAllBloodStock();
            for (Object[] row : rows) stockTableModel.addRow(row);
        }

        if (queueTableModel != null) {
            queueTableModel.setRowCount(0);
            List<Object[]> qRows = dao.getLiveDonorQueue();
            for (Object[] row : qRows) queueTableModel.addRow(row);
        }

        if (reqTableModel != null) {
            reqTableModel.setRowCount(0);
            List<Object[]> reqs = dao.getAllRequests();
            for (Object[] req : reqs) reqTableModel.addRow(req);
        }
    }

    private JButton createStyledButton(String text, Color bg) {
        JButton btn = new JButton(text);
        btn.setFont(new Font("Segoe UI", Font.BOLD, 12));
        btn.setBackground(bg);
        btn.setForeground(Color.WHITE);
        btn.setFocusPainted(false);
        btn.setBorder(new EmptyBorder(8, 14, 8, 14));
        btn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        return btn;
    }

    private void styleTable(JTable table) {
        table.setRowHeight(28);
        table.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        table.setGridColor(new Color(241, 245, 249));

        JTableHeader header = table.getTableHeader();
        header.setFont(new Font("Segoe UI", Font.BOLD, 12));
        header.setBackground(new Color(241, 245, 249));
        header.setForeground(TEXT_DARK);
        header.setPreferredSize(new Dimension(100, 32));

        DefaultTableCellRenderer center = new DefaultTableCellRenderer();
        center.setHorizontalAlignment(JLabel.CENTER);
        for (int i = 0; i < table.getColumnCount(); i++) {
            table.getColumnModel().getColumn(i).setCellRenderer(center);
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new BloodDonationApp().setVisible(true));
    }
}
