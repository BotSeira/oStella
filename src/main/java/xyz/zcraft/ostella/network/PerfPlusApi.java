package xyz.zcraft.ostella.network;

import xyz.zcraft.ostella.oStella;

// TODO
public class PerfPlusApi {
    private static String getEndpoint() {
        return oStella.getConf().performancePlus().endpoint();
    }


}
