import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.io.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import javax.imageio.ImageIO;

// ZXing
import com.google.zxing.*;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;

// OpenCV
import org.opencv.core.*;
import org.opencv.videoio.VideoCapture;
import org.opencv.videoio.Videoio;
import org.opencv.imgproc.Imgproc;

class SmartAttendanceSystem extends JFrame {

    static {
        try {
            System.loadLibrary(Core.NATIVE_LIBRARY_NAME);
        } catch (UnsatisfiedLinkError e) {
            JOptionPane.showMessageDialog(null,
                    "OpenCV DLL not found!",
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ---------------- STUDENT ----------------
    static class Student {
        int roll;
        String name;
        String[] week = new String[12]; // 12 weeks

        Student(int r, String n) {
            roll = r;
            if (isValidName(n)) {
                name = n;
            } else {
                throw new IllegalArgumentException("Invalid name: only letters and spaces are allowed");
            }
            Arrays.fill(week, "Present"); // Initially all Present
        }

        int absentCount() {
            int c = 0;
            for (String s : week)
                if ("Absent".equals(s)) c++;
            return c;
        }

        // Validation method for names
        private static boolean isValidName(String name) {
            return name != null && name.matches("[a-zA-Z ]+");
        }
    }

    // ---------------- DATA ----------------
    private final java.util.List<Student> students = new ArrayList<>();
    private final DefaultTableModel model;
    private final JTable table;
    private final JTextField rollField, nameField, searchField;
    private final JLabel status;
    private final File DATA_FILE = new File("attendance_data.csv");

    // ---------------- CONSTRUCTOR ---------------
    public SmartAttendanceSystem() {

        if (!login()) System.exit(0);

        setTitle("Smart Attendance System");
        setSize(1200, 600);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        // ---------- TOP PANEL ----------
        JPanel top = new JPanel();
        rollField = new JTextField(5);
        nameField = new JTextField(10);
        JButton addBtn = new JButton("Add");
        JButton deleteBtn = new JButton("Delete"); // New Delete Button
        JButton qrBtn = new JButton("Generate QR");
        JButton camBtn = new JButton("Scan QR Camera");
        JButton excelBtn = new JButton("Export Excel");
        JButton markAllPresentBtn = new JButton("Mark All Present");

        top.add(new JLabel("Roll")); top.add(rollField);
        top.add(new JLabel("Name")); top.add(nameField);
        top.add(addBtn); top.add(deleteBtn); top.add(qrBtn); top.add(camBtn); top.add(excelBtn);
        top.add(markAllPresentBtn);
        add(top, BorderLayout.NORTH);

        // ---------- TABLE ----------
        String[] cols = new String[14]; // 2 columns + 12 weeks
        cols[0] = "Roll"; cols[1] = "Name";
        for (int i = 0; i < 12; i++) cols[i + 2] = "Week " + (i + 1);

        model = new DefaultTableModel(cols, 0) {
            public boolean isCellEditable(int r, int c) { return c >= 2; }
        };

        table = new JTable(model);
        table.setRowHeight(25);
        table.setDefaultRenderer(Object.class, new AttendanceRenderer());

        String[] opt = {"Present", "Absent", "Excused", "Late"};
        for (int i = 2; i < 14; i++) {
            table.getColumnModel().getColumn(i)
                    .setCellEditor(new DefaultCellEditor(new JComboBox<>(opt)));
        }

        add(new JScrollPane(table), BorderLayout.CENTER);

        // ---------- BOTTOM PANEL ----------
        JPanel bottom = new JPanel();
        searchField = new JTextField(5);
        JButton searchBtn = new JButton("Search");
        JButton sortRollBtn = new JButton("Sort Roll");
        JButton sortNameBtn = new JButton("Sort Name");

        status = new JLabel(" Ready");
        bottom.add(searchField);
        bottom.add(searchBtn);
        bottom.add(sortRollBtn);
        bottom.add(sortNameBtn);
        bottom.add(status);
        add(bottom, BorderLayout.SOUTH);

        // ---------- ACTIONS ----------
        addBtn.addActionListener(e -> addStudent());
        deleteBtn.addActionListener(e -> deleteStudent());
        qrBtn.addActionListener(e -> generateQR());
        camBtn.addActionListener(e -> scanQRCamera());
        excelBtn.addActionListener(e -> exportExcel());
        searchBtn.addActionListener(e -> search());
        sortRollBtn.addActionListener(e -> quickSortRoll(0, students.size() - 1));
        sortNameBtn.addActionListener(e -> quickSortName(0, students.size() - 1));
        markAllPresentBtn.addActionListener(e -> markAllPresentForWeek());

        table.getModel().addTableModelListener(e -> {
            updateStudentFromTable(e.getFirstRow());
            saveToFile();
            table.repaint();
        });

        loadFromFile();
        setVisible(true);
    }

    // ---------------- LOGIN ----------------
    private boolean login() {
        while (true) {
            JPanel p = new JPanel(new GridLayout(2, 2));
            JTextField u = new JTextField();
            JPasswordField pw = new JPasswordField();
            p.add(new JLabel("User")); p.add(u);
            p.add(new JLabel("Password")); p.add(pw);

            int op = JOptionPane.showConfirmDialog(null, p, "Teacher Login",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

            if (op != JOptionPane.OK_OPTION) return false;

            String username = u.getText().trim();
            String password = new String(pw.getPassword());

            if (username.equals("teacher") && password.equals("123")) {
                return true;
            } else {
                JOptionPane.showMessageDialog(null,
                        "Incorrect username or password.\nPlease try again.",
                        "Login Failed",
                        JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private boolean rollExists(int roll) {
        for (Student s : students) if (s.roll == roll) return true;
        return false;
    }

    // ---------------- CORE ----------------
    private void addStudent() {
        try {
            int roll = Integer.parseInt(rollField.getText().trim());
            String name = nameField.getText().trim();

            if (name.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Name cannot be empty", "Invalid Input", JOptionPane.WARNING_MESSAGE);
                return;
            }

            if (!Student.isValidName(name)) {
                JOptionPane.showMessageDialog(this, "Name can only contain letters and spaces", "Invalid Name", JOptionPane.WARNING_MESSAGE);
                return;
            }

            if (rollExists(roll)) {
                JOptionPane.showMessageDialog(this,
                        "Student with Roll No " + roll + " already exists!",
                        "Duplicate Roll",
                        JOptionPane.ERROR_MESSAGE);
                return;
            }

            Student s = new Student(roll, name);
            students.add(s);
            model.addRow(buildRow(s));
            saveToFile();

            rollField.setText("");
            nameField.setText("");
            status.setText(" Student added: " + name);

        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, "Enter a valid roll number", "Invalid Input", JOptionPane.WARNING_MESSAGE);
        } catch (IllegalArgumentException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Invalid Input", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void deleteStudent() {
        int row = table.getSelectedRow();
        if (row == -1) {
            JOptionPane.showMessageDialog(this, "Select a student to delete", "No Selection", JOptionPane.WARNING_MESSAGE);
            return;
        }

        Student s = students.get(row);
        int confirm = JOptionPane.showConfirmDialog(this,
                "Are you sure you want to delete student: " + s.name + " (Roll " + s.roll + ")?",
                "Confirm Delete", JOptionPane.YES_NO_OPTION);

        if (confirm == JOptionPane.YES_OPTION) {
            students.remove(row);
            model.removeRow(row);
            saveToFile();
            status.setText(" Student deleted: " + s.name);
        }
    }

    private Object[] buildRow(Student s) {
        Object[] r = new Object[14];
        r[0] = s.roll; r[1] = s.name;
        for (int i = 0; i < 12; i++) r[i + 2] = s.week[i];
        return r;
    }

    private void updateStudentFromTable(int row) {
        if (row < 0 || row >= students.size()) return;
        Student s = students.get(row);
        for (int i = 0; i < 12; i++)
            s.week[i] = table.getValueAt(row, i + 2).toString();
    }

    // ---------------- QUICK SORT ----------------
    private   void quickSortRoll(int low, int high) {
        if (low < high) {
            int pi = partitionRoll(low, high);
            quickSortRoll(low, pi - 1);
            quickSortRoll(pi + 1, high);
        } else if (low == 0) {
            refresh();
        }
    }

    private int partitionRoll(int low, int high) {
        int pivot = students.get(high).roll;
        int i = low - 1;
        for (int j = low; j < high; j++) {
            if (students.get(j).roll <= pivot) {
                i++;
                Collections.swap(students, i, j);
            }
        }
        Collections.swap(students, i + 1, high);
        return i + 1;
    }

    private void quickSortName(int low, int high) {
        if (low < high) {
            int pi = partitionName(low, high);
            quickSortName(low, pi - 1);
            quickSortName(pi + 1, high);
        } else if (low == 0) {
            refresh();
        }
    }

    private int partitionName(int low, int high) {
        String pivot = students.get(high).name.toLowerCase();
        int i = low - 1;
        for (int j = low; j < high; j++) {
            if (students.get(j).name.toLowerCase().compareTo(pivot) <= 0) {
                i++;
                Collections.swap(students, i, j);
            }
        }
        Collections.swap(students, i + 1, high);
        return i + 1;
    }

    // ---------------- SEARCH ----------------
    private void search() {
        try {
            int r = Integer.parseInt(searchField.getText());
            for (int i = 0; i < students.size(); i++) {
                if (students.get(i).roll == r) {
                    table.setRowSelectionInterval(i, i);
                    return;
                }
            }
        } catch (Exception ignored) {}
    }

    private void refresh() {
        model.setRowCount(0);
        for (Student s : students) model.addRow(buildRow(s));
    }

    // ---------------- FILE HANDLING ----------------
    private void saveToFile() {
        try (PrintWriter pw = new PrintWriter(DATA_FILE)) {
            for (Student s : students) {
                pw.print(s.roll + "," + s.name);
                for (String w : s.week) pw.print("," + w);
                pw.println();
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void loadFromFile() {
        if (!DATA_FILE.exists()) return;
        try (Scanner sc = new Scanner(DATA_FILE)) {
            while (sc.hasNextLine()) {
                String[] d = sc.nextLine().split(",");
                Student s = new Student(Integer.parseInt(d[0]), d[1]);
                for (int i = 0; i < 12; i++) s.week[i] = d[i + 2];
                students.add(s);
                model.addRow(buildRow(s));
            }
            status.setText(" Data Loaded");
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void exportExcel() {
        saveToFile();
        JOptionPane.showMessageDialog(this,
                "attendance_data.csv exported\n(Open in Excel)");
    }

    // ---------------- MARK ALL PRESENT FOR WEEK ----------------
    private void markAllPresentForWeek() {
        String input = JOptionPane.showInputDialog(this,
                "Enter Week Number (1-12) to mark all Present:");
        if (input == null) return;
        try {
            int weekNum = Integer.parseInt(input.trim());
            if (weekNum < 1 || weekNum > 12) {
                JOptionPane.showMessageDialog(this, "Week number must be 1-12");
                return;
            }

            int colIndex = weekNum + 1;
            for (int row = 0; row < students.size(); row++) {
                students.get(row).week[weekNum - 1] = "Present";
                model.setValueAt("Present", row, colIndex);
            }

            saveToFile();
            status.setText(" All students marked Present for Week " + weekNum);

        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, "Enter a valid number");
        }
    }

    // ---------------- QR ----------------
    private void generateQR() {
        int row = table.getSelectedRow();
        if (row == -1) return;
        Student s = students.get(row);
        try {
            BitMatrix m = new QRCodeWriter().encode(
                    "Roll:" + s.roll,
                    BarcodeFormat.QR_CODE, 200, 200);
            BufferedImage img = new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB);
            for (int x = 0; x < 200; x++)
                for (int y = 0; y < 200; y++)
                    img.setRGB(x, y, m.get(x, y) ? 0 : 0xFFFFFF);
            new File("qrcodes").mkdir();
            ImageIO.write(img, "png", new File("qrcodes/" + s.roll + ".png"));
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void scanQRCamera() {
        new Thread(() -> {
            VideoCapture cam = new VideoCapture(0, Videoio.CAP_DSHOW);
            Mat f = new Mat();
            while (cam.read(f)) {
                Imgproc.cvtColor(f, f, Imgproc.COLOR_BGR2RGB);
                BufferedImage img = matToImg(f);
                try {
                    String d = new MultiFormatReader().decode(
                            new BinaryBitmap(new HybridBinarizer(
                                    new BufferedImageLuminanceSource(img)))).getText();
                    markPresent(d);
                    break;
                } catch (Exception ignored) {}
            }
            cam.release();
        }).start();
    }

    private void markPresent(String d) {
        if (!d.startsWith("Roll:")) return;
        int r = Integer.parseInt(d.split(":")[1]);
        for (Student s : students) {
            if (s.roll == r) {
                for (int i = 0; i < 12; i++)
                    s.week[i] = "Present";
                refresh(); saveToFile(); return;
            }
        }
    }

    private BufferedImage matToImg(Mat m) {
        BufferedImage i = new BufferedImage(m.width(), m.height(), BufferedImage.TYPE_3BYTE_BGR);
        m.get(0, 0, ((DataBufferByte) i.getRaster().getDataBuffer()).getData());
        return i;
    }

    // ---------------- COLOR RENDERER ----------------
    class AttendanceRenderer extends DefaultTableCellRenderer {
        public Component getTableCellRendererComponent(
                JTable t, Object v, boolean sel, boolean foc, int r, int c) {
            Component comp = super.getTableCellRendererComponent(t, v, sel, foc, r, c);
            if (!sel && r < students.size()) {
                int a = students.get(r).absentCount();
                comp.setBackground(
                        a >= 3 ? new Color(255, 150, 150) :
                                a == 2 ? new Color(255, 210, 140) :
                                        a == 1 ? new Color(180, 255, 180) :
                                                new Color(234, 237, 239));
            }
            return comp;
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(SmartAttendanceSystem::new);
    }
}
