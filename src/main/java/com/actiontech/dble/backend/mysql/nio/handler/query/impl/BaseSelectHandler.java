/*
 * Copyright (C) 2016-2020 ActionTech.
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher.
 */

package com.actiontech.dble.backend.mysql.nio.handler.query.impl;

import com.actiontech.dble.DbleServer;
import com.actiontech.dble.backend.datasource.ShardingNode;
import com.actiontech.dble.backend.mysql.CharsetUtil;
import com.actiontech.dble.backend.mysql.nio.handler.query.BaseDMLHandler;
import com.actiontech.dble.net.connection.BackendConnection;
import com.actiontech.dble.net.mysql.ErrorPacket;
import com.actiontech.dble.net.mysql.FieldPacket;
import com.actiontech.dble.net.mysql.RowDataPacket;
import com.actiontech.dble.net.service.AbstractService;
import com.actiontech.dble.plan.common.exception.MySQLOutPutException;
import com.actiontech.dble.route.RouteResultsetNode;
import com.actiontech.dble.server.NonBlockingSession;
import com.actiontech.dble.services.mysqlsharding.MySQLResponseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;

/**
 * for execute Sql,transform the response data to next handler
 */
public class BaseSelectHandler extends BaseDMLHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(BaseSelectHandler.class);

    private final boolean autocommit;
    private volatile int fieldCounts = -1;
    private final RouteResultsetNode rrss;


    public BaseSelectHandler(long id, RouteResultsetNode rrss, boolean autocommit, NonBlockingSession session) {
        super(id, session);
        this.rrss = rrss;
        this.autocommit = autocommit;
    }

    public BackendConnection initConnection() throws Exception {
        if (session.closed()) {
            return null;
        }

        BackendConnection exeConn = session.getTarget(rrss);
        if (session.tryExistsCon(exeConn, rrss)) {
            exeConn.getBackendService().setRowDataFlowing(true);
            exeConn.getBackendService().setResponseHandler(this);
            return exeConn;
        } else {
            ShardingNode dn = DbleServer.getInstance().getConfig().getShardingNodes().get(rrss.getName());
            //autocommit is session.getWriteSource().isAutocommit() && !session.getWriteSource().isTxStart()
            final BackendConnection newConn = dn.getConnection(dn.getDatabase(), rrss.getRunOnSlave(), rrss);
            session.bindConnection(rrss, newConn);
            newConn.getBackendService().setResponseHandler(this);
            newConn.getBackendService().setRowDataFlowing(true);
            return newConn;
        }
    }

    public void execute(MySQLResponseService service) {
        if (session.closed()) {
            service.setRowDataFlowing(false);
            session.clearResources(true);
            return;
        }
        service.setSession(session);
        if (service.getConnection().isClosed()) {
            service.setRowDataFlowing(false);
            session.onQueryError("failed or cancelled by other thread".getBytes());
            return;
        }
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(service.toString() + " send sql:" + rrss.getStatement());
        }
        service.executeMultiNode(rrss, session.getFrontConnection(), autocommit);
    }

    public RouteResultsetNode getRrss() {
        return rrss;
    }

    @Override
    public void okResponse(byte[] ok, AbstractService service) {
        ((MySQLResponseService) service).syncAndExecute();
    }

    @Override
    public void fieldEofResponse(byte[] header, List<byte[]> fields, List<FieldPacket> fieldPacketsNull, byte[] eof,
                                 boolean isLeft, AbstractService service) {
        session.setHandlerEnd(this); //base start receive
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(service.toString() + "'s field is reached.");
        }
        if (terminate.get()) {
            return;
        }
        if (fieldCounts == -1) {
            fieldCounts = fields.size();
        }
        List<FieldPacket> fieldPackets = new ArrayList<>();

        for (byte[] field1 : fields) {
            FieldPacket field = new FieldPacket();
            field.read(field1);
            fieldPackets.add(field);
        }
        nextHandler.fieldEofResponse(null, null, fieldPackets, null, this.isLeft, service);
    }

    @Override
    public boolean rowResponse(byte[] row, RowDataPacket rowPacket, boolean isLeft, AbstractService conn) {
        if (terminate.get())
            return true;
        RowDataPacket rp = new RowDataPacket(fieldCounts);
        rp.read(row);
        nextHandler.rowResponse(null, rp, this.isLeft, conn);
        return false;
    }

    @Override
    public void rowEofResponse(byte[] data, boolean isLeft, AbstractService service) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(service.toString() + " 's rowEof is reached.");
        }
        if (this.terminate.get()) {
            return;
        }
        nextHandler.rowEofResponse(data, this.isLeft, service);
    }

    /**
     * 1. if some connection's thread status is await. 2. if some connection's
     * thread status is running.
     */
    @Override
    public void connectionError(Throwable e, Object attachment) {
        if (terminate.get())
            return;
        String errMsg;
        if (e instanceof MySQLOutPutException) {
            errMsg = e.getMessage() == null ? e.toString() : e.getMessage();
        } else if (e instanceof NullPointerException) {
            errMsg = e.getMessage() == null ? e.toString() : e.getMessage();
        } else {
            RouteResultsetNode node = (RouteResultsetNode) attachment;
            errMsg = "can't connect to shardingNode[" + node.getName() + "],due to " + e.getMessage();
            LOGGER.warn(errMsg);
        }
        session.onQueryError(errMsg.getBytes());
    }

    @Override
    public void connectionClose(AbstractService service, String reason) {
        if (terminate.get())
            return;
        LOGGER.warn(service.toString() + "|connectionClose()|" + reason);
        reason = "Connection {dbInstance[" + service.getConnection().getHost() + ":" + service.getConnection().getPort() + "],Schema[" + ((MySQLResponseService) service).getSchema() + "],threadID[" +
                ((BackendConnection)service.getConnection()).getThreadId() + "]} was closed ,reason is [" + reason + "]";
        session.onQueryError(reason.getBytes());
    }

    @Override
    public void errorResponse(byte[] err, AbstractService service) {
        ErrorPacket errPacket = new ErrorPacket();
        errPacket.read(err);
        String errMsg;
        try {
            errMsg = new String(errPacket.getMessage(), CharsetUtil.getJavaCharset(service.getConnection().getCharsetName().getResults()));
        } catch (UnsupportedEncodingException e) {
            errMsg = "UnsupportedEncodingException:" + service.getConnection().getCharsetName();
        }
        LOGGER.info(service.toString() + errMsg);
        if (terminate.get())
            return;
        session.onQueryError(errMsg.getBytes());
    }

    @Override
    protected void onTerminate() {
        if (autocommit && !session.getShardingService().isLocked()) {
            this.session.releaseConnection(rrss, LOGGER.isDebugEnabled(), false);
        } else {
            //the connection should wait until the connection running finish
            this.session.waitFinishConnection(rrss);
        }
    }

    @Override
    public HandlerType type() {
        return HandlerType.BASESEL;
    }

}
