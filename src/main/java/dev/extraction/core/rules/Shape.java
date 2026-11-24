package dev.extraction.core.rules;

import java.util.ArrayList;
import java.util.List;

/** 网格形状的空格属于画布，只有 X 参与占格。 */
public final class Shape {
    public static final Shape SINGLE = of(List.of("X"));
    private final List<String> rows;
    private final int area;
    private final Shape[] rotations = new Shape[4];
    private final int left;
    private final int top;
    private final int right;
    private final int bottom;

    private Shape(List<String> rows, int area) {
        this.rows = rows;
        this.area = area;
        int minX = rows.getFirst().length();
        int maxX = -1;
        int minY = rows.size();
        int maxY = -1;
        for (int y = 0; y < rows.size(); y++) {
            int first = rows.get(y).indexOf('X');
            if (first >= 0) {
                minX = Math.min(minX, first);
                maxX = Math.max(maxX, rows.get(y).lastIndexOf('X'));
                minY = Math.min(minY, y);
                maxY = y;
            }
        }
        left = minX;
        right = maxX;
        top = minY;
        bottom = maxY;
        rotations[0] = this;
    }

    public static Shape of(List<String> rows) {
        if (rows.isEmpty() || rows.getFirst().isEmpty()) {
            throw new IllegalArgumentException("shape 缺少有效网格");
        }
        int width = rows.getFirst().length();
        int area = 0;
        for (String row : rows) {
            if (row.length() != width) {
                throw new IllegalArgumentException("shape 的每一行必须等宽");
            }
            for (int i = 0; i < width; i++) {
                char cell = row.charAt(i);
                if (cell == 'X') {
                    area++;
                } else if (cell != ' ') {
                    throw new IllegalArgumentException("shape 仅支持大写 X 与半角空格");
                }
            }
        }
        if (area == 0) {
            throw new IllegalArgumentException("shape 必须有占用格");
        }
        return new Shape(List.copyOf(rows), area);
    }

    public List<String> rows() {
        return rows;
    }

    public int width() {
        return rows.getFirst().length();
    }

    public int height() {
        return rows.size();
    }

    public int area() {
        return area;
    }

    public boolean contains(int x, int y) {
        return y >= 0 && y < height() && x >= 0 && x < width() && rows.get(y).charAt(x) == 'X';
    }

    /** 顺时针转过指定个四分之一圈，保留外侧空白。 */
    public synchronized Shape rotate(int rotation) {
        int turns = Math.floorMod(rotation, 4);
        if (rotations[turns] != null) {
            return rotations[turns];
        }
        Shape previous = rotate(turns - 1);
        List<String> rotated = new ArrayList<>(width());
        for (int x = 0; x < previous.width(); x++) {
            StringBuilder row = new StringBuilder(previous.height());
            for (int y = previous.height() - 1; y >= 0; y--) {
                row.append(previous.rows.get(y).charAt(x));
            }
            rotated.add(row.toString());
        }
        rotations[turns] = new Shape(List.copyOf(rotated), area);
        return rotations[turns];
    }

    int left() {
        return left;
    }

    int top() {
        return top;
    }

    int right() {
        return right;
    }

    int bottom() {
        return bottom;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Shape shape && rows.equals(shape.rows);
    }

    @Override
    public int hashCode() {
        return rows.hashCode();
    }
}
