package com.osmb.script.valetotemsfree.model;

public enum ProductType {
    SHORTBOW_U(54, 60, 64, 68, 72, -1),
    LONGBOW_U(56, 58, 62, 66, 70, 31049);

    private final int oakId;
    private final int willowId;
    private final int mapleId;
    private final int yewId;
    private final int magicId;
    private final int redwoodId;

    ProductType(int oakId, int willowId, int mapleId, int yewId, int magicId, int redwoodId) {
        this.oakId = oakId;
        this.willowId = willowId;
        this.mapleId = mapleId;
        this.yewId = yewId;
        this.magicId = magicId;
        this.redwoodId = redwoodId;
    }

    public int getProductId(LogType logType) {
        switch (logType) {
            case OAK:
                return oakId;
            case WILLOW:
                return willowId;
            case MAPLE:
                return mapleId;
            case YEW:
                return yewId;
            case MAGIC:
                return magicId;
            case REDWOOD:
                return redwoodId;
            default:
                return 56;
        }
    }
}
