package com.actiontech.dble.services.manager;

import com.actiontech.dble.config.Capabilities;
import com.actiontech.dble.config.ErrorCode;
import com.actiontech.dble.config.model.SystemConfig;
import com.actiontech.dble.config.model.user.ManagerUserConfig;
import com.actiontech.dble.config.model.user.UserName;
import com.actiontech.dble.net.connection.AbstractConnection;
import com.actiontech.dble.net.mysql.AuthPacket;
import com.actiontech.dble.net.mysql.MySQLPacket;
import com.actiontech.dble.net.mysql.PingPacket;
import com.actiontech.dble.net.service.AuthResultInfo;
import com.actiontech.dble.net.service.FrontEndService;
import com.actiontech.dble.route.parser.util.Pair;
import com.actiontech.dble.services.MySQLBasedService;
import com.actiontech.dble.singleton.FrontendUserManager;

import java.io.UnsupportedEncodingException;

/**
 * Created by szf on 2020/6/28.
 */
public class ManagerService extends MySQLBasedService implements FrontEndService {

    private final ManagerQueryHandler handler;

    protected UserName user;


    public ManagerService(AbstractConnection connection) {
        super(connection);
        this.handler = new ManagerQueryHandler(this);
    }

    public void initFromAuthInfo(AuthResultInfo info) {
        AuthPacket auth = info.getMysqlAuthPacket();
        this.user = new UserName(auth.getUser(), auth.getTenant());
        this.userConfig = info.getUserConfig();
        connection.initCharsetIndex(info.getMysqlAuthPacket().getCharsetIndex());
        this.clientFlags = info.getMysqlAuthPacket().getClientFlags();
        boolean clientCompress = Capabilities.CLIENT_COMPRESS == (Capabilities.CLIENT_COMPRESS & auth.getClientFlags());
        boolean usingCompress = SystemConfig.getInstance().getUseCompression() == 1;
        if (clientCompress && usingCompress) {
            this.setSupportCompress(true);
        }
    }


    @Override
    protected void handleInnerData(byte[] data) {
        switch (data[4]) {
            case MySQLPacket.COM_QUERY:
                //commands.doQuery();
                try {
                    handler.query(proto.getSQL(data, this.getConnection().getCharsetName()));
                } catch (UnsupportedEncodingException e) {
                    writeErrMessage(ErrorCode.ER_UNKNOWN_CHARACTER_SET, "Unknown charset '" + this.getConnection().getCharsetName().getClient() + "'");
                }
                break;
            case MySQLPacket.COM_PING:
                //commands.doPing();
                PingPacket.response(this);
                break;
            case MySQLPacket.COM_QUIT:
                //commands.doQuit();
                connection.close("quit cmd");
                break;
            default:
                //commands.doOther();
                this.writeErrMessage(ErrorCode.ER_UNKNOWN_COM_ERROR, "Unknown command");
        }
    }

    @Override
    public void markFinished() {

    }

    public ManagerUserConfig getUserConfig() {
        return (ManagerUserConfig) userConfig;
    }

    @Override
    public void userConnectionCount() {
        FrontendUserManager.getInstance().countDown(user, false);
    }

    @Override
    public UserName getUser() {
        return user;
    }

    @Override
    public String getExecuteSql() {
        return "";
    }


}
