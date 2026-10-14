package com.brandon.nightsky.processing;

/**
 * 3x3 rotation helpers. Matrices are row-major double[9]; R maps a vector v to R*v.
 */
public final class Rot {
    private Rot() {}

    public static double[] identity() {
        return new double[]{1, 0, 0, 0, 1, 0, 0, 0, 1};
    }

    public static double[] mul(double[] a, double[] b) {
        double[] c = new double[9];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                c[i * 3 + j] = a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j];
            }
        }
        return c;
    }

    public static double[] transpose(double[] a) {
        return new double[]{a[0], a[3], a[6], a[1], a[4], a[7], a[2], a[5], a[8]};
    }

    /** out = R * (x, y, z) */
    public static void apply(double[] r, double x, double y, double z, double[] out) {
        double ox = r[0] * x + r[1] * y + r[2] * z;
        double oy = r[3] * x + r[4] * y + r[5] * z;
        double oz = r[6] * x + r[7] * y + r[8] * z;
        out[0] = ox;
        out[1] = oy;
        out[2] = oz;
    }

    /** Rodrigues: rotation of |r| radians about r/|r|. */
    public static double[] fromRotVec(double rx, double ry, double rz) {
        double th2 = rx * rx + ry * ry + rz * rz;
        double th = Math.sqrt(th2);
        double a, b;
        if (th < 1e-6) {
            a = 1 - th2 / 6;
            b = 0.5 - th2 / 24;
        } else {
            a = Math.sin(th) / th;
            b = (1 - Math.cos(th)) / th2;
        }
        double xx = rx * rx, yy = ry * ry, zz = rz * rz, xy = rx * ry, xz = rx * rz, yz = ry * rz;
        double[] R = new double[9];
        R[0] = 1 - b * (yy + zz);
        R[1] = -a * rz + b * xy;
        R[2] = a * ry + b * xz;
        R[3] = a * rz + b * xy;
        R[4] = 1 - b * (xx + zz);
        R[5] = -a * rx + b * yz;
        R[6] = -a * ry + b * xz;
        R[7] = a * rx + b * yz;
        R[8] = 1 - b * (xx + yy);
        return R;
    }

    public static double[] fromRotVec(double[] r) {
        return fromRotVec(r[0], r[1], r[2]);
    }

    /** Inverse of fromRotVec for rotations below ~179 degrees. */
    public static double[] toRotVec(double[] R) {
        double tr = R[0] + R[4] + R[8];
        double c = Stats.clamp((tr - 1) / 2, -1.0, 1.0);
        double th = Math.acos(c);
        double vx = R[7] - R[5], vy = R[2] - R[6], vz = R[3] - R[1];
        double f;
        if (th < 1e-6) {
            f = 0.5 * (1 + th * th / 6);
        } else {
            f = th / (2 * Math.sin(th));
        }
        return new double[]{vx * f, vy * f, vz * f};
    }

    /** Rotation angle (radians) of R. */
    public static double angle(double[] R) {
        double tr = R[0] + R[4] + R[8];
        return Math.acos(Stats.clamp((tr - 1) / 2, -1.0, 1.0));
    }

    /** Unit quaternion (scalar first) to rotation matrix. */
    public static double[] fromQuat(double q0, double qx, double qy, double qz) {
        double n = Math.sqrt(q0 * q0 + qx * qx + qy * qy + qz * qz);
        if (n < 1e-300) return identity();
        q0 /= n;
        qx /= n;
        qy /= n;
        qz /= n;
        double[] R = new double[9];
        R[0] = q0 * q0 + qx * qx - qy * qy - qz * qz;
        R[1] = 2 * (qx * qy - q0 * qz);
        R[2] = 2 * (qx * qz + q0 * qy);
        R[3] = 2 * (qy * qx + q0 * qz);
        R[4] = q0 * q0 - qx * qx + qy * qy - qz * qz;
        R[5] = 2 * (qy * qz - q0 * qx);
        R[6] = 2 * (qz * qx - q0 * qy);
        R[7] = 2 * (qz * qy + q0 * qx);
        R[8] = q0 * q0 - qx * qx - qy * qy + qz * qz;
        return R;
    }

    /**
     * Weighted least-squares rotation R with b_i ~= R a_i (Horn's quaternion method).
     * a and b hold n packed xyz unit vectors. idx (optional) selects which pairs to use.
     */
    public static double[] solve(double[] a, double[] b, double[] w, int[] idx, int n) {
        double sxx = 0, sxy = 0, sxz = 0, syx = 0, syy = 0, syz = 0, szx = 0, szy = 0, szz = 0;
        for (int k = 0; k < n; k++) {
            int i = idx == null ? k : idx[k];
            double wi = w == null ? 1.0 : w[i];
            double ax = a[3 * i], ay = a[3 * i + 1], az = a[3 * i + 2];
            double bx = b[3 * i], by = b[3 * i + 1], bz = b[3 * i + 2];
            sxx += wi * ax * bx;
            sxy += wi * ax * by;
            sxz += wi * ax * bz;
            syx += wi * ay * bx;
            syy += wi * ay * by;
            syz += wi * ay * bz;
            szx += wi * az * bx;
            szy += wi * az * by;
            szz += wi * az * bz;
        }
        double[][] N = {
                {sxx + syy + szz, syz - szy, szx - sxz, sxy - syx},
                {syz - szy, sxx - syy - szz, sxy + syx, szx + sxz},
                {szx - sxz, sxy + syx, -sxx + syy - szz, syz + szy},
                {sxy - syx, szx + sxz, syz + szy, -sxx - syy + szz}};
        double[] ev = new double[4];
        double[][] V = new double[4][4];
        jacobi(N, ev, V);
        int best = 0;
        for (int i = 1; i < 4; i++) if (ev[i] > ev[best]) best = i;
        return fromQuat(V[0][best], V[1][best], V[2][best], V[3][best]);
    }

    /** Cyclic Jacobi eigen-decomposition of a small symmetric matrix. Columns of V are eigenvectors. */
    public static void jacobi(double[][] A0, double[] d, double[][] V) {
        int n = A0.length;
        double[][] A = new double[n][n];
        for (int i = 0; i < n; i++) {
            System.arraycopy(A0[i], 0, A[i], 0, n);
            for (int j = 0; j < n; j++) V[i][j] = i == j ? 1 : 0;
        }
        for (int sweep = 0; sweep < 60; sweep++) {
            double off = 0, diag = 0;
            for (int i = 0; i < n; i++) {
                diag += A[i][i] * A[i][i];
                for (int j = i + 1; j < n; j++) off += A[i][j] * A[i][j];
            }
            if (off <= 1e-30 * Math.max(diag, 1e-300)) break;
            for (int p = 0; p < n; p++) {
                for (int q = p + 1; q < n; q++) {
                    double apq = A[p][q];
                    if (Math.abs(apq) < 1e-300) continue;
                    double theta = (A[q][q] - A[p][p]) / (2 * apq);
                    double t = (theta >= 0 ? 1.0 : -1.0) / (Math.abs(theta) + Math.sqrt(theta * theta + 1));
                    double c = 1 / Math.sqrt(t * t + 1), s = t * c;
                    for (int k = 0; k < n; k++) {
                        double akp = A[k][p], akq = A[k][q];
                        A[k][p] = c * akp - s * akq;
                        A[k][q] = s * akp + c * akq;
                    }
                    for (int k = 0; k < n; k++) {
                        double apk = A[p][k], aqk = A[q][k];
                        A[p][k] = c * apk - s * aqk;
                        A[q][k] = s * apk + c * aqk;
                    }
                    for (int k = 0; k < n; k++) {
                        double vkp = V[k][p], vkq = V[k][q];
                        V[k][p] = c * vkp - s * vkq;
                        V[k][q] = s * vkp + c * vkq;
                    }
                }
            }
        }
        for (int i = 0; i < n; i++) d[i] = A[i][i];
    }

    /** Inverse of a general 3x3 matrix (row-major), or null if singular. */
    public static double[] inverse(double[] m) {
        double a = m[0], b = m[1], c = m[2], d = m[3], e = m[4], f = m[5], g = m[6], h = m[7], i = m[8];
        double A = e * i - f * h, B = -(d * i - f * g), C = d * h - e * g;
        double det = a * A + b * B + c * C;
        if (Math.abs(det) < 1e-300) return null;
        double inv = 1 / det;
        return new double[]{
                A * inv, -(b * i - c * h) * inv, (b * f - c * e) * inv,
                B * inv, (a * i - c * g) * inv, -(a * f - c * d) * inv,
                C * inv, -(a * h - b * g) * inv, (a * e - b * d) * inv};
    }

    public static double[] mulVec(double[] m, double[] v) {
        return new double[]{
                m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
                m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
                m[6] * v[0] + m[7] * v[1] + m[8] * v[2]};
    }

    public static double[] diag(double x, double y, double z) {
        return new double[]{x, 0, 0, 0, y, 0, 0, 0, z};
    }

    public static double[] scale(double[] m, double s) {
        double[] r = new double[9];
        for (int i = 0; i < 9; i++) r[i] = m[i] * s;
        return r;
    }

    public static double[] lerp(double[] a, double[] b, double t) {
        double[] r = new double[9];
        for (int i = 0; i < 9; i++) r[i] = a[i] + (b[i] - a[i]) * t;
        return r;
    }
}
