import java.awt.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;

public class LabManagementSystem {
    static class LabException extends Exception {

        LabException(String msg) {
            super(msg);
        }
    }

    /**
     * Sample life-cycle. The allowed transitions live in the enum itself
     * (single source of truth).
     */
    enum SampleStatus {
        PENDING, COLLECTED, PROCESSING, COMPLETED, REJECTED;

        boolean canMoveTo(SampleStatus n) {
            switch (this) {
                case PENDING:
                    return n == COLLECTED || n == REJECTED;
                case COLLECTED:
                    return n == PROCESSING || n == REJECTED;
                case PROCESSING:
                    return n == COMPLETED || n == REJECTED;
                default:
                    return false; // COMPLETED and REJECTED are final
            }
        }
    }

    static class Patient {

        private final String id;
        private String name, gender, phone;
        private LocalDate dob;

        Patient(String id, String name, LocalDate dob, String gender, String phone) {
            this.id = id;
            this.name = name;
            this.dob = dob;
            this.gender = gender;
            this.phone = phone;
        }

        String getId() {
            return id;
        }

        String getName() {
            return name;
        }

        LocalDate getDob() {
            return dob;
        }

        String getGender() {
            return gender;
        }

        String getPhone() {
            return phone;
        }

        int getAge() {
            return java.time.Period.between(dob, LocalDate.now()).getYears();
        }

        void update(String name, LocalDate dob, String gender, String phone) {
            this.name = name;
            this.dob = dob;
            this.gender = gender;
            this.phone = phone;
        }

        String toCsv() {
            return String.join(",", id, clean(name), dob.toString(), gender, phone);
        }

        static Patient fromCsv(String line) {
            String[] p = line.split(",", -1);
            return new Patient(p[0], p[1], LocalDate.parse(p[2]), p[3], p[4]);
        }
    }

    static class TestDefinition {

        private final String code;
        private final String name, unit;
        private final double price, refLow, refHigh;
        private final int turnaroundDays;

        TestDefinition(String code, String name, String unit, double price,
                double refLow, double refHigh, int turnaroundDays) {
            this.code = code;
            this.name = name;
            this.unit = unit;
            this.price = price;
            this.refLow = refLow;
            this.refHigh = refHigh;
            this.turnaroundDays = turnaroundDays;
        }

        String getCode() {
            return code;
        }

        String getName() {
            return name;
        }

        String getUnit() {
            return unit;
        }

        double getPrice() {
            return price;
        }

        double getRefLow() {
            return refLow;
        }

        double getRefHigh() {
            return refHigh;
        }

        int getTurnaroundDays() {
            return turnaroundDays;
        }

        String range() {
            return refLow + " - " + refHigh + " " + unit;
        }

        String toCsv() {
            return String.join(",", code, clean(name), clean(unit),
                    String.valueOf(price), String.valueOf(refLow), String.valueOf(refHigh),
                    String.valueOf(turnaroundDays));
        }

        static TestDefinition fromCsv(String line) {
            String[] p = line.split(",", -1);
            return new TestDefinition(p[0], p[1], p[2], Double.parseDouble(p[3]),
                    Double.parseDouble(p[4]), Double.parseDouble(p[5]), Integer.parseInt(p[6]));
        }
    }

    static class TestRequest {

        private final String id, patientId, testCode;
        private final LocalDate requestDate, dueDate;
        private SampleStatus status;
        private Double result;          // null until a result is entered
        private LocalDate resultDate;   // null until completed

        TestRequest(String id, String patientId, String testCode, LocalDate requestDate,
                LocalDate dueDate, SampleStatus status, Double result, LocalDate resultDate) {
            this.id = id;
            this.patientId = patientId;
            this.testCode = testCode;
            this.requestDate = requestDate;
            this.dueDate = dueDate;
            this.status = status;
            this.result = result;
            this.resultDate = resultDate;
        }

        String getId() {
            return id;
        }

        String getPatientId() {
            return patientId;
        }

        String getTestCode() {
            return testCode;
        }

        LocalDate getRequestDate() {
            return requestDate;
        }

        LocalDate getDueDate() {
            return dueDate;
        }

        SampleStatus getStatus() {
            return status;
        }

        Double getResult() {
            return result;
        }

        LocalDate getResultDate() {
            return resultDate;
        }

        void setStatus(SampleStatus s) {
            status = s;
        }

        void setResult(double r, LocalDate d) {
            result = r;
            resultDate = d;
        }

        /**
         * Overdue = not finished (not COMPLETED/REJECTED) and past the due
         * date.
         */
        boolean isOverdue() {
            return (status != SampleStatus.COMPLETED && status != SampleStatus.REJECTED)
                    && LocalDate.now().isAfter(dueDate);
        }

        String toCsv() {
            return String.join(",", id, patientId, testCode, requestDate.toString(), dueDate.toString(),
                    status.name(), result == null ? "-" : result.toString(),
                    resultDate == null ? "-" : resultDate.toString());
        }

        static TestRequest fromCsv(String line) {
            String[] p = line.split(",", -1);
            return new TestRequest(p[0], p[1], p[2], LocalDate.parse(p[3]), LocalDate.parse(p[4]),
                    SampleStatus.valueOf(p[5]),
                    p[6].equals("-") ? null : Double.valueOf(p[6]),
                    p[7].equals("-") ? null : LocalDate.parse(p[7]));
        }
    }

    /**
     * Commas would corrupt our CSV, so they are neutralised on write.
     */
    static String clean(String s) {
        return s.replace(",", " ").replace("\n", " ").trim();
    }

    // =====================================================================
    // 3. SERVICE LAYER - all business rules and validation live here, never in the GUI.
    // =====================================================================
    static class LabService {

        // LinkedHashMap: O(1) lookup by ID + keeps insertion order.
        private final Map<String, Patient> patients = new LinkedHashMap<>();
        private final Map<String, TestDefinition> tests = new LinkedHashMap<>();
        // ArrayList: ordered, many requests per patient, sorted on demand with Comparators.
        private final List<TestRequest> requests = new ArrayList<>();

    
        static final Map<String, Comparator<Patient>> PATIENT_SORTS = new LinkedHashMap<>();
        static final Map<String, Comparator<TestDefinition>> TEST_SORTS = new LinkedHashMap<>();
        static final Map<String, Comparator<TestRequest>> REQUEST_SORTS = new LinkedHashMap<>();

        static {
            PATIENT_SORTS.put("ID", Comparator.comparing(Patient::getId));
            PATIENT_SORTS.put("Name (A-Z)", Comparator.comparing(Patient::getName, String.CASE_INSENSITIVE_ORDER));
            PATIENT_SORTS.put("Age (oldest first)", Comparator.comparing(Patient::getDob));
            TEST_SORTS.put("Code", Comparator.comparing(TestDefinition::getCode));
            TEST_SORTS.put("Name (A-Z)", Comparator.comparing(TestDefinition::getName, String.CASE_INSENSITIVE_ORDER));
            TEST_SORTS.put("Price (low-high)", Comparator.comparingDouble(TestDefinition::getPrice));
            REQUEST_SORTS.put("Request ID", Comparator.comparing(TestRequest::getId));
            REQUEST_SORTS.put("Due date (earliest)", Comparator.comparing(TestRequest::getDueDate)
                    .thenComparing(TestRequest::getId));
            REQUEST_SORTS.put("Status", Comparator.comparing(TestRequest::getStatus)
                    .thenComparing(TestRequest::getDueDate));
            REQUEST_SORTS.put("Patient ID", Comparator.comparing(TestRequest::getPatientId)
                    .thenComparing(TestRequest::getRequestDate));
        }

        // ---------- Patients ----------
        void addPatient(String id, String name, String dob, String gender, String phone) throws LabException {
            id = required(id, "Patient ID").toUpperCase();
            if (patients.containsKey(id)) {
                throw new LabException("Patient ID " + id + " already exists.");
            }
            patients.put(id, new Patient(id, validName(name), validDob(dob), gender, validPhone(phone)));
        }

        void updatePatient(String id, String name, String dob, String gender, String phone) throws LabException {
            getPatient(id).update(validName(name), validDob(dob), gender, validPhone(phone));
        }

        void deletePatient(String id) throws LabException {
            Patient p = getPatient(id);
            // Business rule: never orphan medical records.
            boolean hasRequests = requests.stream().anyMatch(r -> r.getPatientId().equals(p.getId()));
            if (hasRequests) {
                throw new LabException("Cannot delete " + p.getId() + ": patient has test requests.");
            }
            patients.remove(p.getId());
        }

        Patient getPatient(String id) throws LabException {
            Patient p = patients.get(required(id, "Patient ID").toUpperCase());
            if (p == null) {
                throw new LabException("No patient found with ID " + id.trim().toUpperCase());
            }
            return p;
        }

        List<Patient> searchPatients(String keyword, Comparator<Patient> order) {
            String k = keyword == null ? "" : keyword.trim().toLowerCase();
            return patients.values().stream()
                    .filter(p -> k.isEmpty() || p.getId().toLowerCase().contains(k)
                    || p.getName().toLowerCase().contains(k) || p.getPhone().contains(k))
                    .sorted(order).collect(Collectors.toList());
        }

        // ---------- Test definitions ----------
        void addTest(String code, String name, String unit, String price, String low, String high, String tat)
                throws LabException {
            code = required(code, "Test code").toUpperCase();
            if (tests.containsKey(code)) {
                throw new LabException("Test code " + code + " already exists.");
            }
            double pr = number(price, "Price"), lo = number(low, "Reference low"), hi = number(high, "Reference high");
            if (pr <= 0) {
                throw new LabException("Price must be greater than zero.");
            }
            if (lo >= hi) {
                throw new LabException("Reference low must be less than reference high.");
            }
            int days;
            try {
                days = Integer.parseInt(tat.trim());
            } catch (NumberFormatException e) {
                throw new LabException("Turnaround days must be a whole number.");
            }
            if (days < 0 || days > 30) {
                throw new LabException("Turnaround days must be between 0 and 30.");
            }
            tests.put(code, new TestDefinition(code, required(name, "Test name"), required(unit, "Unit"), pr, lo, hi, days));
        }

        void deleteTest(String code) throws LabException {
            TestDefinition t = getTest(code);
            if (requests.stream().anyMatch(r -> r.getTestCode().equals(t.getCode()))) {
                throw new LabException("Cannot delete " + t.getCode() + ": it is used in test requests.");
            }
            tests.remove(t.getCode());
        }

        TestDefinition getTest(String code) throws LabException {
            TestDefinition t = tests.get(required(code, "Test code").toUpperCase());
            if (t == null) {
                throw new LabException("No test found with code " + code.trim().toUpperCase());
            }
            return t;
        }

        List<TestDefinition> listTests(Comparator<TestDefinition> order) {
            return tests.values().stream().sorted(order).collect(Collectors.toList());
        }

        // ---------- Requests ----------
        TestRequest registerRequest(String patientId, String testCode, String date) throws LabException {
            Patient p = getPatient(patientId);
            TestDefinition t = getTest(testCode);
            LocalDate reqDate = parseDate(date, "Request date");
            if (reqDate.isBefore(LocalDate.now())) {
                throw new LabException("Request date cannot be in the past.");
            }
            // Business rule: no duplicate active request for same patient + test + day.
            boolean dup = requests.stream().anyMatch(r -> r.getPatientId().equals(p.getId())
                    && r.getTestCode().equals(t.getCode()) && r.getRequestDate().equals(reqDate)
                    && r.getStatus() != SampleStatus.REJECTED);
            if (dup) {
                throw new LabException("A request for this patient and test already exists on " + reqDate + ".");
            }
            String id = String.format("R%04d", nextRequestNumber());
            // LocalDate arithmetic: due date = request date + test turnaround time
            TestRequest r = new TestRequest(id, p.getId(), t.getCode(), reqDate,
                    reqDate.plusDays(t.getTurnaroundDays()), SampleStatus.PENDING, null, null);
            requests.add(r);
            return r;
        }

        TestRequest getRequest(String id) throws LabException {
            final String key = required(id, "Request ID");
            return requests.stream().filter(r -> r.getId().equalsIgnoreCase(key))
                    .findFirst().orElseThrow(() -> new LabException("No request found with ID " + key));
        }

        void changeStatus(String id, SampleStatus next) throws LabException {
            TestRequest r = getRequest(id);
            if (next == SampleStatus.COMPLETED) {
                throw new LabException("A request becomes COMPLETED only when a result is entered.");
            }
            if (!r.getStatus().canMoveTo(next)) {
                throw new LabException("Invalid transition: " + r.getStatus() + " -> " + next);
            }
            r.setStatus(next);
        }

        void enterResult(String id, String value) throws LabException {
            TestRequest r = getRequest(id);
            if (r.getStatus() != SampleStatus.PROCESSING) {
                throw new LabException("Result can be entered only while status is PROCESSING (current: " + r.getStatus() + ").");
            }
            double v = number(value, "Result value");
            if (v < 0) {
                throw new LabException("Result value cannot be negative.");
            }
            r.setResult(v, LocalDate.now());
            r.setStatus(SampleStatus.COMPLETED);
        }

        List<TestRequest> listRequests(SampleStatus filter, Comparator<TestRequest> order) {
            return requests.stream().filter(r -> filter == null || r.getStatus() == filter)
                    .sorted(order).collect(Collectors.toList());
        }

        private int nextRequestNumber() {
            return requests.stream().mapToInt(r -> Integer.parseInt(r.getId().substring(1))).max().orElse(0) + 1;
        }

        // ---------- Result interpretation ----------
        String flag(TestRequest r) {
            if (r.getResult() == null) {
                return "-";
            }
            TestDefinition t = tests.get(r.getTestCode());
            if (t == null) {
                return "?";
            }
            return r.getResult() < t.getRefLow() ? "LOW" : r.getResult() > t.getRefHigh() ? "HIGH" : "NORMAL";
        }

        // ---------- Reports & summary ----------
        String patientReport(String patientId) throws LabException {
            Patient p = getPatient(patientId);
            List<TestRequest> mine = requests.stream().filter(r -> r.getPatientId().equals(p.getId()))
                    .sorted(Comparator.comparing(TestRequest::getRequestDate).thenComparing(TestRequest::getId))
                    .collect(Collectors.toList());
            StringBuilder sb = new StringBuilder();
            String line = "=".repeat(78) + "\n";
            sb.append(line).append("                 DIAGNOSTIC LABORATORY - PATIENT REPORT\n").append(line);
            sb.append(String.format("Patient : %s (%s)%nAge/Sex: %d / %s    Phone: %s%nPrinted : %s%n",
                    p.getName(), p.getId(), p.getAge(), p.getGender(), p.getPhone(), LocalDate.now()));
            sb.append("-".repeat(78)).append('\n');
            sb.append(String.format("%-7s %-18s %-10s %-10s %-9s %-13s %-8s%n",
                    "Req", "Test", "Requested", "Status", "Result", "Ref. range", "Flag"));
            sb.append("-".repeat(78)).append('\n');
            double billed = 0;
            for (TestRequest r : mine) {
                TestDefinition t = tests.get(r.getTestCode());
                String res = r.getResult() == null ? "-" : r.getResult() + " " + t.getUnit();
                sb.append(String.format("%-7s %-18s %-10s %-10s %-9s %-13s %-8s%n", r.getId(),
                        trunc(t.getName(), 18), r.getRequestDate(), r.getStatus(),
                        trunc(res, 9), trunc(t.getRefLow() + "-" + t.getRefHigh(), 13), flag(r)));
                if (r.getStatus() != SampleStatus.REJECTED) {
                    billed += t.getPrice();
                }
            }
            if (mine.isEmpty()) {
                sb.append("(no test requests on record)\n");
            }
            sb.append("-".repeat(78)).append('\n');
            sb.append(String.format("Total billable amount (excluding rejected): Rs. %.2f%n", billed));
            sb.append("Note: results outside the reference range must be reviewed by a physician.\n");
            return sb.toString();
        }

        String summary() {
            Map<SampleStatus, Long> byStatus = new EnumMap<>(SampleStatus.class);
            for (SampleStatus s : SampleStatus.values()) {
                byStatus.put(s, 0L);
            }
            requests.forEach(r -> byStatus.merge(r.getStatus(), 1L, Long::sum));
            long overdue = requests.stream().filter(TestRequest::isOverdue).count();
            long abnormal = requests.stream().filter(r -> r.getResult() != null)
                    .filter(r -> !flag(r).equals("NORMAL")).count();
            double revenue = requests.stream().filter(r -> r.getStatus() == SampleStatus.COMPLETED)
                    .mapToDouble(r -> tests.get(r.getTestCode()).getPrice()).sum();
            StringBuilder sb = new StringBuilder("LABORATORY SUMMARY  (" + LocalDate.now() + ")\n");
            sb.append("-".repeat(40)).append('\n');
            sb.append("Registered patients : ").append(patients.size()).append('\n');
            sb.append("Test definitions    : ").append(tests.size()).append('\n');
            sb.append("Total requests      : ").append(requests.size()).append('\n');
            byStatus.forEach((s, c) -> sb.append(String.format("  %-12s: %d%n", s, c)));
            sb.append("Overdue requests    : ").append(overdue).append('\n');
            sb.append("Abnormal results    : ").append(abnormal).append('\n');
            sb.append(String.format("Revenue (completed) : Rs. %.2f%n", revenue));
            return sb.toString();
        }

        /**
         * Used by the requests table: "OVERDUE" / days left.
         */
        String dueInfo(TestRequest r) {
            if (r.getStatus() == SampleStatus.COMPLETED || r.getStatus() == SampleStatus.REJECTED) {
                return "-";
            }
            long d = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), r.getDueDate());
            return d < 0 ? "OVERDUE by " + (-d) + "d" : d + " day(s) left";
        }

        // ---------- File I/O ----------
        void save(Path dir) throws LabException {
            try {
                Files.createDirectories(dir);
                write(dir.resolve("patients.csv"), patients.values().stream().map(Patient::toCsv).collect(Collectors.toList()));
                write(dir.resolve("tests.csv"), tests.values().stream().map(TestDefinition::toCsv).collect(Collectors.toList()));
                write(dir.resolve("requests.csv"), requests.stream().map(TestRequest::toCsv).collect(Collectors.toList()));
            } catch (IOException e) {
                throw new LabException("Could not save data: " + e.getMessage());
            }
        }

        void load(Path dir) throws LabException {
            patients.clear();
            tests.clear();
            requests.clear();
            try {
                for (String l : read(dir.resolve("patients.csv"))) {
                    Patient p = Patient.fromCsv(l);
                    patients.put(p.getId(), p);
                }
                for (String l : read(dir.resolve("tests.csv"))) {
                    TestDefinition t = TestDefinition.fromCsv(l);
                    tests.put(t.getCode(), t);
                }
                for (String l : read(dir.resolve("requests.csv"))) {
                    requests.add(TestRequest.fromCsv(l));
                }
            } catch (IOException | RuntimeException e) {
                // RuntimeException covers corrupted lines (bad number / date / enum / missing column)
                patients.clear();
                tests.clear();
                requests.clear();
                throw new LabException("Data files are unreadable or corrupted: " + e.getMessage());
            }
        }

        private void write(Path f, List<String> lines) throws IOException {
            try (BufferedWriter w = Files.newBufferedWriter(f, StandardCharsets.UTF_8)) {
                for (String l : lines) {
                    w.write(l);
                    w.newLine();
                }
            }
        }

        private List<String> read(Path f) throws IOException {
            if (!Files.exists(f)) {
                return Collections.emptyList(); // first run: nothing stored yet

                        }List<String> out = new ArrayList<>();
            try (BufferedReader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                String l;
                while ((l = r.readLine()) != null) {
                    if (!l.isBlank()) {
                        out.add(l);
                    }
                }
            }
            return out;
        }

        void loadSampleData() throws LabException {
            addPatient("P001", "Asha Verma", "1990-05-14", "Female", "9876543210");
            addPatient("P002", "Rahul Deshmukh", "1982-11-02", "Male", "9123456780");
            addTest("CBC", "Hemoglobin", "g/dL", "250", "12", "16", "1");
            addTest("GLU", "Fasting Glucose", "mg/dL", "120", "70", "100", "1");
            addTest("CHOL", "Total Cholesterol", "mg/dL", "300", "125", "200", "2");
        }

        // ---------- validation helpers ----------
        private static String required(String s, String field) throws LabException {
            if (s == null || s.trim().isEmpty()) {
                throw new LabException(field + " cannot be empty.");
            }
            return s.trim();
        }

        private static String validName(String s) throws LabException {
            s = required(s, "Name");
            if (!s.matches("[A-Za-z .'-]{2,60}")) {
                throw new LabException("Name must contain only letters (2-60 chars).");
            }
            return s;
        }

        private static String validPhone(String s) throws LabException {
            s = required(s, "Phone");
            if (!s.matches("\\d{10}")) {
                throw new LabException("Phone must be exactly 10 digits.");
            }
            return s;
        }

        private static LocalDate parseDate(String s, String field) throws LabException {
            try {
                return LocalDate.parse(required(s, field));
            } catch (DateTimeParseException e) {
                throw new LabException(field + " must be in yyyy-MM-dd format.");
            }
        }

        private static LocalDate validDob(String s) throws LabException {
            LocalDate d = parseDate(s, "Date of birth");
            if (d.isAfter(LocalDate.now())) {
                throw new LabException("Date of birth cannot be in the future.");
            }
            if (d.isBefore(LocalDate.now().minusYears(130))) {
                throw new LabException("Date of birth is unrealistic.");
            }
            return d;
        }

        private static double number(String s, String field) throws LabException {
            try {
                return Double.parseDouble(required(s, field));
            } catch (NumberFormatException e) {
                throw new LabException(field + " must be a number.");
            }
        }
    }

    static String trunc(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n - 1) + "~";
    }

    // =====================================================================
    // 4. GUI (Swing). It only collects input, calls the service and shows messages.
    // =====================================================================
    static class MainFrame extends JFrame {

        private final LabService svc = new LabService();
        private final Path dataDir = Paths.get("labdata");

        // Patients tab
        private final JTextField pId = new JTextField(8), pName = new JTextField(14), pDob = new JTextField(9),
                pPhone = new JTextField(10), pSearch = new JTextField(12);
        private final JComboBox<String> pGender = new JComboBox<>(new String[]{"Female", "Male", "Other"});
        private final JComboBox<String> pSort = new JComboBox<>(LabService.PATIENT_SORTS.keySet().toArray(new String[0]));
        private final DefaultTableModel pModel = model("ID", "Name", "DOB", "Age", "Gender", "Phone");

        // Tests tab
        private final JTextField tCode = new JTextField(6), tName = new JTextField(12), tUnit = new JTextField(6),
                tPrice = new JTextField(6), tLow = new JTextField(5), tHigh = new JTextField(5), tTat = new JTextField(3);
        private final JComboBox<String> tSort = new JComboBox<>(LabService.TEST_SORTS.keySet().toArray(new String[0]));
        private final DefaultTableModel tModel = model("Code", "Name", "Unit", "Price", "Ref. range", "TAT (days)");

        // Requests tab
        private final JTextField rPatient = new JTextField(7), rTest = new JTextField(6),
                rDate = new JTextField(LocalDate.now().toString(), 9), rId = new JTextField(7), rResult = new JTextField(6);
        private final JComboBox<String> rFilter = new JComboBox<>(statusItems());
        private final JComboBox<String> rSort = new JComboBox<>(LabService.REQUEST_SORTS.keySet().toArray(new String[0]));
        private final DefaultTableModel rModel = model("Req ID", "Patient", "Test", "Requested", "Due", "Status",
                "Result", "Flag", "Timing");

        // Reports tab
        private final JTextField repPatient = new JTextField(8);
        private final JTextArea repArea = mono();
        private final JTextArea sumArea = mono();

        MainFrame() {
            super("Medical Laboratory Test Management System");
            setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
            addWindowListener(new java.awt.event.WindowAdapter() {
                @Override
                public void windowClosing(java.awt.event.WindowEvent e) {
                    saveAndExit();
                }
            });
            JTabbedPane tabs = new JTabbedPane();
            tabs.addTab("Patients", patientsTab());
            tabs.addTab("Test Catalogue", testsTab());
            tabs.addTab("Test Requests", requestsTab());
            tabs.addTab("Reports", reportsTab());
            tabs.addTab("Summary", summaryTab());
            tabs.addChangeListener(e -> {
                refreshAll();
            });
            add(tabs);
            startup();
            setSize(980, 600);
            setLocationRelativeTo(null);
        }

        private void startup() {
            try {
                svc.load(dataDir);
                if (svc.searchPatients("", LabService.PATIENT_SORTS.get("ID")).isEmpty()
                        && svc.listTests(LabService.TEST_SORTS.get("Code")).isEmpty()) {
                    svc.loadSampleData();
                }
            } catch (LabException e) {
                warn(e.getMessage() + "\nStarting with sample data.");
                try {
                    svc.loadSampleData();
                } catch (LabException ignored) {
                }
            }
            refreshAll();
        }

        private void saveAndExit() {
            try {
                svc.save(dataDir);
            } catch (LabException e) {
                if (JOptionPane.showConfirmDialog(this, e.getMessage() + "\nExit without saving?", "Save failed",
                        JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
                    return;
                }
            }
            dispose();
            System.exit(0);
        }

        // ---------------- tabs ----------------
        private JPanel patientsTab() {
            JPanel form = flow(new JLabel("ID"), pId, new JLabel("Name"), pName, new JLabel("DOB (yyyy-MM-dd)"), pDob,
                    new JLabel("Gender"), pGender, new JLabel("Phone"), pPhone);
            JPanel buttons = flow(
                    btn("Add", () -> act(() -> svc.addPatient(pId.getText(), pName.getText(), pDob.getText(),
                    (String) pGender.getSelectedItem(), pPhone.getText()), "Patient added.")),
                    btn("Update", () -> act(() -> svc.updatePatient(pId.getText(), pName.getText(), pDob.getText(),
                    (String) pGender.getSelectedItem(), pPhone.getText()), "Patient updated.")),
                    btn("Delete", () -> act(() -> svc.deletePatient(pId.getText()), "Patient deleted.")),
                    btn("Clear", () -> {
                        pId.setText("");
                        pName.setText("");
                        pDob.setText("");
                        pPhone.setText("");
                    }),
                    new JLabel("   Search"), pSearch, btn("Go", this::refreshPatients),
                    new JLabel("Sort by"), pSort);
            pSort.addActionListener(e -> refreshPatients());
            JTable table = new JTable(pModel);
            table.getSelectionModel().addListSelectionListener(e -> {
                int r = table.getSelectedRow();
                if (r >= 0 && !e.getValueIsAdjusting()) {
                    pId.setText(str(pModel, r, 0));
                    pName.setText(str(pModel, r, 1));
                    pDob.setText(str(pModel, r, 2));
                    pGender.setSelectedItem(str(pModel, r, 4));
                    pPhone.setText(str(pModel, r, 5));
                }
            });
            return page(stack(form, buttons), table);
        }

        private JPanel testsTab() {
            JPanel form = flow(new JLabel("Code"), tCode, new JLabel("Name"), tName, new JLabel("Unit"), tUnit,
                    new JLabel("Price"), tPrice, new JLabel("Ref low"), tLow, new JLabel("Ref high"), tHigh,
                    new JLabel("TAT days"), tTat);
            JPanel buttons = flow(
                    btn("Add Test", () -> act(() -> svc.addTest(tCode.getText(), tName.getText(), tUnit.getText(),
                    tPrice.getText(), tLow.getText(), tHigh.getText(), tTat.getText()), "Test added.")),
                    btn("Delete Test", () -> act(() -> svc.deleteTest(tCode.getText()), "Test deleted.")),
                    new JLabel("   Sort by"), tSort);
            tSort.addActionListener(e -> refreshTests());
            JTable table = new JTable(tModel);
            table.getSelectionModel().addListSelectionListener(e -> {
                int r = table.getSelectedRow();
                if (r >= 0 && !e.getValueIsAdjusting()) {
                    tCode.setText(str(tModel, r, 0));
                }
            });
            return page(stack(form, buttons), table);
        }

        private JPanel requestsTab() {
            JPanel reg = flow(new JLabel("Patient ID"), rPatient, new JLabel("Test code"), rTest,
                    new JLabel("Date"), rDate,
                    btn("Register Request", () -> act(() -> {
                TestRequest r = svc.registerRequest(rPatient.getText(), rTest.getText(), rDate.getText());
                rId.setText(r.getId());
            }, "Request registered.")));
            JPanel upd = flow(new JLabel("Request ID"), rId,
                    btn("Sample Collected", () -> status(SampleStatus.COLLECTED)),
                    btn("Start Processing", () -> status(SampleStatus.PROCESSING)),
                    btn("Reject Sample", () -> status(SampleStatus.REJECTED)),
                    new JLabel("  Result"), rResult,
                    btn("Save Result", () -> act(() -> svc.enterResult(rId.getText(), rResult.getText()), "Result saved.")));
            JPanel view = flow(new JLabel("Filter"), rFilter, new JLabel("Sort by"), rSort);
            rFilter.addActionListener(e -> refreshRequests());
            rSort.addActionListener(e -> refreshRequests());
            JTable table = new JTable(rModel);
            table.getSelectionModel().addListSelectionListener(e -> {
                int r = table.getSelectedRow();
                if (r >= 0 && !e.getValueIsAdjusting()) {
                    rId.setText(str(rModel, r, 0));
                    rPatient.setText(str(rModel, r, 1));
                    rTest.setText(str(rModel, r, 2));
                }
            });
            return page(stack(reg, upd, view), table);
        }

        private JPanel reportsTab() {
            JPanel top = flow(new JLabel("Patient ID"), repPatient,
                    btn("Generate Report", () -> {
                        try {
                            repArea.setText(svc.patientReport(repPatient.getText()));
                            repArea.setCaretPosition(0);
                        } catch (LabException e) {
                            warn(e.getMessage());
                        }
                    }),
                    btn("Save Report to File", this::saveReport));
            JPanel p = new JPanel(new BorderLayout());
            p.add(top, BorderLayout.NORTH);
            p.add(new JScrollPane(repArea), BorderLayout.CENTER);
            return p;
        }

        private JPanel summaryTab() {
            JPanel p = new JPanel(new BorderLayout());
            p.add(flow(btn("Refresh", () -> sumArea.setText(svc.summary()))), BorderLayout.NORTH);
            p.add(new JScrollPane(sumArea), BorderLayout.CENTER);
            return p;
        }

        // ---------------- actions & refresh ----------------
        private interface Action {

            void run() throws LabException;
        }

        /**
         * Runs a service call; shows the business-rule message on failure,
         * success message otherwise.
         */
        private void act(Action a, String okMsg) {
            try {
                a.run();
                refreshAll();
                info(okMsg);
            } catch (LabException e) {
                warn(e.getMessage());
            } catch (RuntimeException e) {
                warn("Unexpected error: " + e);
            }
        }

        private void status(SampleStatus s) {
            act(() -> svc.changeStatus(rId.getText(), s), "Status updated to " + s + ".");
        }

        private void saveReport() {
            if (repArea.getText().isBlank()) {
                warn("Generate a report first.");
                return;
            }
            Path f = Paths.get("labdata", "report_" + repPatient.getText().trim().toUpperCase() + "_" + LocalDate.now() + ".txt");
            try {
                Files.createDirectories(f.getParent());
                Files.write(f, repArea.getText().getBytes(StandardCharsets.UTF_8));
                info("Report saved to " + f.toAbsolutePath());
            } catch (IOException e) {
                warn("Could not save report: " + e.getMessage());
            }
        }

        private void refreshAll() {
            refreshPatients();
            refreshTests();
            refreshRequests();
            sumArea.setText(svc.summary());
        }

        private void refreshPatients() {
            pModel.setRowCount(0);
            for (Patient p : svc.searchPatients(pSearch.getText(), LabService.PATIENT_SORTS.get((String) pSort.getSelectedItem()))) {
                pModel.addRow(new Object[]{p.getId(), p.getName(), p.getDob(), p.getAge(), p.getGender(), p.getPhone()});
            }
        }

        private void refreshTests() {
            tModel.setRowCount(0);
            for (TestDefinition t : svc.listTests(LabService.TEST_SORTS.get((String) tSort.getSelectedItem()))) {
                tModel.addRow(new Object[]{t.getCode(), t.getName(), t.getUnit(), t.getPrice(), t.range(), t.getTurnaroundDays()});
            }
        }

        private void refreshRequests() {
            rModel.setRowCount(0);
            String f = (String) rFilter.getSelectedItem();
            SampleStatus filter = (f == null || f.equals("ALL")) ? null : SampleStatus.valueOf(f);
            for (TestRequest r : svc.listRequests(filter, LabService.REQUEST_SORTS.get((String) rSort.getSelectedItem()))) {
                rModel.addRow(new Object[]{r.getId(), r.getPatientId(), r.getTestCode(), r.getRequestDate(), r.getDueDate(),
                    r.getStatus(), r.getResult() == null ? "-" : r.getResult(), svc.flag(r), svc.dueInfo(r)});
            }
        }

        // ---------------- small UI helpers ----------------
        private static String[] statusItems() {
            List<String> l = new ArrayList<>();
            l.add("ALL");
            for (SampleStatus s : SampleStatus.values()) {
                l.add(s.name());
            }
            return l.toArray(new String[0]);
        }

        private static DefaultTableModel model(String... cols) {
            return new DefaultTableModel(cols, 0) {
                @Override
                public boolean isCellEditable(int r, int c) {
                    return false;
                }
            };
        }

        private static String str(DefaultTableModel m, int r, int c) {
            return String.valueOf(m.getValueAt(r, c));
        }

        private static JTextArea mono() {
            JTextArea a = new JTextArea();
            a.setEditable(false);
            a.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
            return a;
        }

        private static JButton btn(String text, Runnable r) {
            JButton b = new JButton(text);
            b.addActionListener(e -> r.run());
            return b;
        }

        private static JPanel flow(Component... cs) {
            JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
            for (Component c : cs) {
                p.add(c);
            
            }return p;
        }

        private static JPanel stack(JPanel... ps) {
            JPanel p = new JPanel();
            p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
            for (JPanel x : ps) {
                p.add(x);
            
            }return p;
        }

        private static JPanel page(JPanel top, JTable t) {
            JPanel p = new JPanel(new BorderLayout());
            p.add(top, BorderLayout.NORTH);
            t.setAutoCreateRowSorter(false);
            t.setFillsViewportHeight(true);
            p.add(new JScrollPane(t), BorderLayout.CENTER);
            return p;
        }

        private void warn(String m) {
            JOptionPane.showMessageDialog(this, m, "Error", JOptionPane.ERROR_MESSAGE);
        }

        private void info(String m) {
            JOptionPane.showMessageDialog(this, m, "Success", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new MainFrame().setVisible(true));
    }
}
