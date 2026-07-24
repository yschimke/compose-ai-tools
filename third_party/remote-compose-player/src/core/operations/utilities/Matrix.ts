// Matrix: 4x4 transformation matrix utility, matching Java Matrix.java

export class Matrix {
    mDim0 = 4;
    mDim1 = 4;
    mMatrix: Float32Array;

    static readonly sTmpMatrix1 = new Matrix();
    static readonly sTmpMatrix2 = new Matrix();
    static sTempOutVec: Float32Array | null = null;
    static sTempInVec: Float32Array | null = null;

    constructor(dim0 = 4, dim1 = 4) {
        this.mDim0 = dim0;
        this.mDim1 = dim1;
        this.mMatrix = new Float32Array(dim0 * dim1);
        this.setIdentity();
    }

    setDimensions(dim0: number, dim1: number): void {
        this.mDim0 = dim0;
        this.mDim1 = dim1;
        this.mMatrix = new Float32Array(dim0 * dim1);
    }

    setIdentity(): void {
        this.mMatrix.fill(0);
        const min = Math.min(this.mDim0, this.mDim1);
        for (let i = 0; i < min; i++) {
            this.mMatrix[i * this.mDim1 + i] = 1.0;
        }
    }

    copyFromMatrix(src: Matrix): void {
        this.setDimensions(src.mDim0, src.mDim1);
        for (let i = 0; i < this.mMatrix.length; i++) {
            this.mMatrix[i] = src.mMatrix[i];
        }
    }

    copyFrom(values: number[] | Float32Array): void {
        if (values.length === 16) {
            for (let i = 0; i < 16; i++) {
                this.mMatrix[i] = values[i];
            }
        } else if (values.length === 9) {
            this.mMatrix[0] = values[0];
            this.mMatrix[1] = values[1];
            this.mMatrix[3] = values[2];
            this.mMatrix[4] = values[3];
            this.mMatrix[5] = values[4];
            this.mMatrix[6] = values[5];
            this.mMatrix[8] = values[6];
            this.mMatrix[9] = values[7];
            this.mMatrix[10] = values[8];
            this.mMatrix[11] = 0;
            this.mMatrix[12] = 0;
            this.mMatrix[13] = 0;
            this.mMatrix[14] = 0;
            this.mMatrix[15] = 1;
        }
    }

    get(row: number, col: number): number {
        return this.mMatrix[row * this.mDim1 + col];
    }

    set(row: number, col: number, value: number): void {
        this.mMatrix[row * this.mDim1 + col] = value;
    }

    putValues(dest: number[] | Float32Array): void {
        for (let i = 0; i < dest.length; i++) {
            dest[i] = this.mMatrix[i];
        }
    }

    static multiply(a: Matrix, b: Matrix, dest: Matrix): void {
        dest.setDimensions(a.mDim0, b.mDim1);
        for (let i = 0; i < dest.mDim0; i++) {
            for (let j = 0; j < dest.mDim1; j++) {
                let sum = 0;
                for (let k = 0; k < a.mDim1; k++) {
                    sum += a.mMatrix[i * a.mDim1 + k] * b.mMatrix[k * b.mDim1 + j];
                }
                dest.mMatrix[i * dest.mDim1 + j] = sum;
            }
        }
    }

    static copy(src: Matrix, dest: Matrix): void {
        dest.setDimensions(src.mDim0, src.mDim1);
        for (let i = 0; i < src.mMatrix.length; i++) {
            dest.mMatrix[i] = src.mMatrix[i];
        }
    }

    rotateX(degrees: number): void {
        const rad = degrees * Math.PI / 180;
        const cos = Math.cos(rad);
        const sin = Math.sin(rad);
        const tmp1 = Matrix.sTmpMatrix1;
        const tmp2 = Matrix.sTmpMatrix2;
        tmp1.setIdentity();
        tmp1.set(1, 1, cos);
        tmp1.set(1, 2, -sin);
        tmp1.set(2, 1, sin);
        tmp1.set(2, 2, cos);
        Matrix.multiply(this, tmp1, tmp2);
        this.copyFromMatrix(tmp2);
    }

    rotateY(degrees: number): void {
        const rad = degrees * Math.PI / 180;
        const cos = Math.cos(rad);
        const sin = Math.sin(rad);
        const tmp1 = Matrix.sTmpMatrix1;
        const tmp2 = Matrix.sTmpMatrix2;
        tmp1.setIdentity();
        tmp1.set(0, 0, cos);
        tmp1.set(0, 2, sin);
        tmp1.set(2, 0, -sin);
        tmp1.set(2, 2, cos);
        Matrix.multiply(this, tmp1, tmp2);
        this.copyFromMatrix(tmp2);
    }

    rotateZ(degrees: number): void;
    rotateZ(pivotX: number, pivotY: number, degrees: number): void;
    rotateZ(a: number, b?: number, c?: number): void {
        if (b !== undefined && c !== undefined) {
            this.rotateZWithPivot(a, b, c);
            return;
        }
        const degrees = a;
        const rad = degrees * Math.PI / 180;
        const cos = Math.cos(rad);
        const sin = Math.sin(rad);
        const tmp1 = Matrix.sTmpMatrix1;
        const tmp2 = Matrix.sTmpMatrix2;
        tmp1.setIdentity();
        tmp1.set(0, 0, cos);
        tmp1.set(0, 1, -sin);
        tmp1.set(1, 0, sin);
        tmp1.set(1, 1, cos);
        Matrix.multiply(this, tmp1, tmp2);
        this.copyFromMatrix(tmp2);
    }

    private rotateZWithPivot(pivotX: number, pivotY: number, degrees: number): void {
        const rad = degrees * Math.PI / 180;
        const cos = Math.cos(rad);
        const sin = Math.sin(rad);
        const oneMinusCos = 1 - cos;
        const tx = pivotX * oneMinusCos + pivotY * sin;
        const ty = pivotY * oneMinusCos - pivotX * sin;

        const result = new Float32Array(16);
        for (let i = 0; i < 4; i++) {
            for (let j = 0; j < 4; j++) {
                let sum = 0;
                for (let k = 0; k < 4; k++) {
                    let m_ik: number;
                    if (i === 0) {
                        if (k === 0) m_ik = cos;
                        else if (k === 1) m_ik = -sin;
                        else if (k === 3) m_ik = tx;
                        else m_ik = 0;
                    } else if (i === 1) {
                        if (k === 0) m_ik = sin;
                        else if (k === 1) m_ik = cos;
                        else if (k === 3) m_ik = ty;
                        else m_ik = 0;
                    } else if (i === 2) {
                        m_ik = k === 2 ? 1 : 0;
                    } else {
                        m_ik = k === 3 ? 1 : 0;
                    }
                    sum += m_ik * this.mMatrix[k * 4 + j];
                }
                result[i * 4 + j] = sum;
            }
        }
        this.mMatrix.set(result);
    }

    translate(x: number, y: number, z: number): void {
        const tmp1 = Matrix.sTmpMatrix1;
        const tmp2 = Matrix.sTmpMatrix2;
        tmp1.setIdentity();
        tmp1.set(0, 3, x);
        tmp1.set(1, 3, y);
        tmp1.set(2, 3, z);
        Matrix.multiply(this, tmp1, tmp2);
        this.copyFromMatrix(tmp2);
    }

    setScale(x: number, y: number, z: number): void {
        this.mMatrix[0 * this.mDim1 + 0] *= x;
        this.mMatrix[1 * this.mDim1 + 1] *= y;
        this.mMatrix[2 * this.mDim1 + 2] *= z;
    }

    projection(fovDegrees: number, aspectRatio: number, near: number, far: number): void {
        const tmp1 = Matrix.sTmpMatrix1;
        const tmp2 = Matrix.sTmpMatrix2;
        const matrix = tmp1.mMatrix;

        const fovRadians = fovDegrees * Math.PI / 180;
        const f = 1 / Math.tan(fovRadians / 2);
        const rangeInv = 1 / (near - far);

        matrix[0] = f / aspectRatio;
        matrix[1] = 0; matrix[2] = 0; matrix[3] = 0;
        matrix[4] = 0; matrix[5] = f; matrix[6] = 0; matrix[7] = 0;
        matrix[8] = 0; matrix[9] = 0;
        matrix[10] = (far + near) * rangeInv;
        matrix[11] = -1;
        matrix[12] = 0; matrix[13] = 0;
        matrix[14] = 2 * far * near * rangeInv;
        matrix[15] = 0;

        Matrix.multiply(this, tmp1, tmp2);
        this.copyFromMatrix(tmp2);
    }

    rotateAroundAxis(vx: number, vy: number, vz: number, angleDegrees: number): void {
        const angleRadians = angleDegrees * Math.PI / 180;
        const lenSq = vx * vx + vy * vy + vz * vz;
        if (lenSq === 0) return;

        const len = Math.sqrt(lenSq);
        const ux = vx / len, uy = vy / len, uz = vz / len;
        const cos = Math.cos(angleRadians);
        const sin = Math.sin(angleRadians);
        const omc = 1 - cos;

        const r00 = cos + ux * ux * omc;
        const r01 = ux * uy * omc - uz * sin;
        const r02 = ux * uz * omc + uy * sin;
        const r10 = uy * ux * omc + uz * sin;
        const r11 = cos + uy * uy * omc;
        const r12 = uy * uz * omc - ux * sin;
        const r20 = uz * ux * omc - uy * sin;
        const r21 = uz * uy * omc + ux * sin;
        const r22 = cos + uz * uz * omc;

        const result = new Float32Array(16);
        for (let i = 0; i < 4; i++) {
            for (let j = 0; j < 4; j++) {
                let sum = 0;
                for (let k = 0; k < 4; k++) {
                    let r_ik: number;
                    if (i === 0) {
                        r_ik = k === 0 ? r00 : k === 1 ? r01 : k === 2 ? r02 : 0;
                    } else if (i === 1) {
                        r_ik = k === 0 ? r10 : k === 1 ? r11 : k === 2 ? r12 : 0;
                    } else if (i === 2) {
                        r_ik = k === 0 ? r20 : k === 1 ? r21 : k === 2 ? r22 : 0;
                    } else {
                        r_ik = k === 3 ? 1 : 0;
                    }
                    sum += r_ik * this.mMatrix[k * 4 + j];
                }
                result[i * 4 + j] = sum;
            }
        }
        this.mMatrix.set(result);
    }

    /** Matrix × vector multiplication */
    multiplyVec(input: number[] | Float32Array, out: number[] | Float32Array): void {
        for (let j = 0; j < out.length; j++) {
            let tmp = 0;
            for (let i = 0; i < input.length; i++) {
                tmp += this.mMatrix[i + j * 4] * input[i];
            }
            out[j] = tmp + this.mMatrix[3 + j * 4];
        }
    }

    /** Perspective transform: matrix × vector, then divide by w */
    evalPerspective(input: number[] | Float32Array, out: number[] | Float32Array): void {
        if (Matrix.sTempInVec === null) {
            Matrix.sTempInVec = new Float32Array(4);
            Matrix.sTempOutVec = new Float32Array(4);
            Matrix.sTempInVec[3] = 1;
        }
        const inVec = Matrix.sTempInVec!;
        const outVec = Matrix.sTempOutVec!;

        for (let i = 0; i < input.length; i++) inVec[i] = input[i];

        for (let j = 0; j < outVec.length; j++) {
            let tmp = 0;
            for (let i = 0; i < inVec.length; i++) {
                tmp += this.mMatrix[i + j * 4] * inVec[i];
            }
            outVec[j] = tmp;
        }

        for (let i = 0; i < out.length; i++) {
            outVec[i] /= outVec[3];
        }

        for (let i = 0; i < out.length; i++) out[i] = outVec[i];
    }
}
