package cn.wangfan.gflight;

public final class CoordinateMapper {
    private CoordinateMapper() {}

    /** Maps a device-frame vector to Android's East, North, Up world frame. */
    public static float[] deviceToWorld(float[] rotationMatrix, float x, float y, float z) {
        if (rotationMatrix == null || rotationMatrix.length < 9) {
            return new float[] {Float.NaN, Float.NaN, Float.NaN};
        }
        return new float[] {
                rotationMatrix[0] * x + rotationMatrix[1] * y + rotationMatrix[2] * z,
                rotationMatrix[3] * x + rotationMatrix[4] * y + rotationMatrix[5] * z,
                rotationMatrix[6] * x + rotationMatrix[7] * y + rotationMatrix[8] * z
        };
    }
}
