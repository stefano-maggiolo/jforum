/*
 * Copyright (c) JForum Team
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, 
 * with or without modification, are permitted provided 
 * that the following conditions are met:
 * 
 * 1) Redistributions of source code must retain the above 
 * copyright notice, this list of conditions and the 
 * following disclaimer.
 * 2) Redistributions in binary form must reproduce the 
 * above copyright notice, this list of conditions and 
 * the following disclaimer in the documentation and/or 
 * other materials provided with the distribution.
 * 3) Neither the name of "Rafael Steil" nor 
 * the names of its contributors may be used to endorse 
 * or promote products derived from this software without 
 * specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT 
 * HOLDERS AND CONTRIBUTORS "AS IS" AND ANY 
 * EXPRESS OR IMPLIED WARRANTIES, INCLUDING, 
 * BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF 
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR 
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL 
 * THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE 
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, 
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES 
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF 
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, 
 * OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER 
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER 
 * IN CONTRACT, STRICT LIABILITY, OR TORT 
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN 
 * ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF 
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE
 * 
 * Created on 20/05/2004 - 15:51:10
 * The JForum Project
 * http://www.jforum.net
 */
package net.jforum.dao.generic;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import net.jforum.JForumExecutionContext;
import net.jforum.dao.DataAccessDriver;
import net.jforum.dao.UserDAO;
import net.jforum.entities.Post;
import net.jforum.entities.PrivateMessage;
import net.jforum.entities.PrivateMessageType;
import net.jforum.entities.User;
import net.jforum.exceptions.DatabaseException;
import net.jforum.util.DbUtils;
import net.jforum.util.preferences.ConfigKeys;
import net.jforum.util.preferences.SystemGlobals;

/**
 * @author Rafael Steil
 * @version $Id$
 */
public class GenericPrivateMessageDAO extends AutoKeys implements net.jforum.dao.PrivateMessageDAO {
    /**
     * @see net.jforum.dao.PrivateMessageDAO#send(net.jforum.entities.PrivateMessage)
     */
    public void send(PrivateMessage pm) {
        // We should store 2 copies: one for the sendee's sent box
        // and another for the target user's inbox.
        PreparedStatement pstmt = null;

        try {
            pstmt = this.getStatementForAutoKeys("PrivateMessageModel.add");

            // Sendee's sent box
            this.addPm(pm, pstmt);
            this.addPmText(pm);

            // Target user's inbox
            pstmt.setInt(1, PrivateMessageType.NEW);
            pm.setId(this.executeAutoKeysQuery(pstmt));

            this.addPmText(pm);
        } catch (Exception e) {
            throw new DatabaseException(e);
        } finally {
            DbUtils.close(pstmt);
        }
    }

    protected void addPmText(PrivateMessage pm) throws Exception {
        PreparedStatement text = null;

        try {
            text = JForumExecutionContext.getConnection().prepareStatement(SystemGlobals.getSql("PrivateMessagesModel.addText"));

            text.setInt(1, pm.getId());
            text.setString(2, pm.getPost().getText());
            text.executeUpdate();
        } finally {
            DbUtils.close(text);
        }
    }

    protected void addPm(PrivateMessage pm, PreparedStatement pstmt) throws SQLException {
        pstmt.setInt(1, PrivateMessageType.SENT);
        pstmt.setString(2, pm.getPost().getSubject());
        pstmt.setInt(3, pm.getFromUser().getId());
        pstmt.setInt(4, pm.getToUser().getId());
        pstmt.setTimestamp(5, new Timestamp(pm.getPost().getTime().getTime()));
        pstmt.setInt(6, pm.getPost().isBbCodeEnabled() ? 1 : 0);
        pstmt.setInt(7, pm.getPost().isHtmlEnabled() ? 1 : 0);
        pstmt.setInt(8, pm.getPost().isSmiliesEnabled() ? 1 : 0);
        pstmt.setInt(9, pm.getPost().isSignatureEnabled() ? 1 : 0);

        this.setAutoGeneratedKeysQuery(SystemGlobals.getSql("PrivateMessagesModel.lastGeneratedPmId"));
        pm.setId(this.executeAutoKeysQuery(pstmt));
    }

    /**
     * @see net.jforum.dao.PrivateMessageDAO#delete(net.jforum.entities.PrivateMessage[], int)
     */
    public void delete(PrivateMessage[] pm, int userId) {
        PreparedStatement deleteMessage = null;
        PreparedStatement deleteText = null;
        PreparedStatement isDeleteAllowed = null;

        try {
            Connection connection = JForumExecutionContext.getConnection();

            deleteMessage = connection.prepareStatement(SystemGlobals.getSql("PrivateMessageModel.delete"));
            deleteText = connection.prepareStatement(SystemGlobals.getSql("PrivateMessagesModel.deleteText"));

            isDeleteAllowed = connection.prepareStatement(SystemGlobals.getSql("PrivateMessagesModel.isDeleteAllowed"));
            isDeleteAllowed.setInt(2, userId);
            isDeleteAllowed.setInt(3, userId);

            for (int i = 0; i < pm.length; i++) {
                PrivateMessage currentMessage = pm[i];

                isDeleteAllowed.setInt(1, currentMessage.getId());

                ResultSet rs = null;

                try {
                    rs = isDeleteAllowed.executeQuery();

                    if (rs.next()) {
                        deleteText.setInt(1, currentMessage.getId());
                        deleteText.executeUpdate();

                        deleteMessage.setInt(1, currentMessage.getId());
                        deleteMessage.executeUpdate();
                    }
                } finally {
                    DbUtils.close(rs);
                }
            }
        } catch (SQLException e) {
            throw new DatabaseException(e);
        } finally {
            DbUtils.close(deleteMessage);
            DbUtils.close(deleteText);
            DbUtils.close(isDeleteAllowed);
        }
    }

    /**
     * @see net.jforum.dao.PrivateMessageDAO#selectFromInbox(net.jforum.entities.User)
     */
    public List<PrivateMessage> selectFromInbox(User user) {
        String query = SystemGlobals.getSql("PrivateMessageModel.baseListing");
        query = query.replaceAll("#FILTER#", SystemGlobals.getSql("PrivateMessageModel.inbox"));

        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try {
            pstmt = JForumExecutionContext.getConnection().prepareStatement(query);
            pstmt.setInt(1, user.getId());

            List<PrivateMessage> pmList = new ArrayList<PrivateMessage>();

            rs = pstmt.executeQuery();
            while (rs.next()) {
                PrivateMessage pm = this.getPm(rs, false);

                User fromUser = new User();
                fromUser.setId(rs.getInt("user_id"));
                fromUser.setUsername(rs.getString("username"));

                pm.setFromUser(fromUser);

                pmList.add(pm);
            }

            return pmList;
        } catch (SQLException e) {
            throw new DatabaseException(e);
        } finally {
            DbUtils.close(rs, pstmt);
        }
    }

    /**
     * @see net.jforum.dao.PrivateMessageDAO#selectFromSent(net.jforum.entities.User)
     */
    public List<PrivateMessage> selectFromSent(User user) {
        String query = SystemGlobals.getSql("PrivateMessageModel.baseListing");
        query = query.replaceAll("#FILTER#", SystemGlobals.getSql("PrivateMessageModel.sent"));

        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try {
            pstmt = JForumExecutionContext.getConnection().prepareStatement(query);
            pstmt.setInt(1, user.getId());

            List<PrivateMessage> pmList = new ArrayList<PrivateMessage>();

            rs = pstmt.executeQuery();
            while (rs.next()) {
                PrivateMessage pm = this.getPm(rs, false);

                User toUser = new User();
                toUser.setId(rs.getInt("user_id"));
                toUser.setUsername(rs.getString("username"));

                pm.setToUser(toUser);

                pmList.add(pm);
            }
            return pmList;
        } catch (SQLException e) {
            throw new DatabaseException(e);
        } finally {
            DbUtils.close(rs, pstmt);
        }
    }

    protected PrivateMessage getPm(ResultSet rs) throws SQLException {
        return this.getPm(rs, true);
    }

    protected PrivateMessage getPm(ResultSet rs, boolean full) throws SQLException {
        PrivateMessage pm = new PrivateMessage();
        Post post = new Post();

        pm.setId(rs.getInt("privmsgs_id"));
        pm.setType(rs.getInt("privmsgs_type"));
        post.setTime(new Date(rs.getTimestamp("privmsgs_date").getTime()));
        post.setSubject(rs.getString("privmsgs_subject"));
        pm.setHasAttachments(rs.getInt("privmsgs_attach") > 0);

        SimpleDateFormat df = new SimpleDateFormat(SystemGlobals.getValue(ConfigKeys.DATE_TIME_FORMAT), Locale.getDefault());
        pm.setFormattedDate(df.format(post.getTime()));

        if (full) {
            UserDAO um = DataAccessDriver.getInstance().newUserDAO();
            pm.setFromUser(um.selectById(rs.getInt("privmsgs_from_userid")));
            pm.setToUser(um.selectById(rs.getInt("privmsgs_to_userid")));

            post.setBbCodeEnabled(rs.getInt("privmsgs_enable_bbcode") == 1);
            post.setSignatureEnabled(rs.getInt("privmsgs_attach_sig") == 1);
            post.setHtmlEnabled(rs.getInt("privmsgs_enable_html") == 1);
            post.setSmiliesEnabled(rs.getInt("privmsgs_enable_smilies") == 1);
            post.setText(this.getPmText(rs));
        }

        pm.setPost(post);

        return pm;
    }

    protected String getPmText(ResultSet rs) throws SQLException {
        return rs.getString("privmsgs_text");
    }

    /**
     * @see net.jforum.dao.PrivateMessageDAO#selectById(net.jforum.entities.PrivateMessage)
     */
    public PrivateMessage selectById(PrivateMessage origPrivMsg) {
        PrivateMessage pm = origPrivMsg;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try {
            pstmt = JForumExecutionContext.getConnection().prepareStatement(
                    SystemGlobals.getSql("PrivateMessageModel.selectById"));
            pstmt.setInt(1, pm.getId());

            rs = pstmt.executeQuery();
            if (rs.next()) {
                pm = this.getPm(rs);
            }

            return pm;
        } catch (SQLException e) {
            throw new DatabaseException(e);
        } finally {
            DbUtils.close(rs, pstmt);
        }
    }

    /**
     * @see net.jforum.dao.PrivateMessageDAO#updateType(net.jforum.entities.PrivateMessage)
     */
    public void updateType(PrivateMessage pm) {
        PreparedStatement pstmt = null;
        try {
            pstmt = JForumExecutionContext.getConnection().prepareStatement(
                    SystemGlobals.getSql("PrivateMessageModel.updateType"));
            pstmt.setInt(1, pm.getType());
            pstmt.setInt(2, pm.getId());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            throw new DatabaseException(e);
        } finally {
            DbUtils.close(pstmt);
        }
    }

    @Override
    public int inboxTotalCount(User user) {
        String query = SystemGlobals.getSql("PrivateMessageModel.baseCount");
        query = query.replaceAll("#FILTER#", SystemGlobals.getSql("PrivateMessageModel.inbox"));

        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try {
            pstmt = JForumExecutionContext.getConnection().prepareStatement(query);
            pstmt.setInt(1, user.getId());

            rs = pstmt.executeQuery();
            if (rs.next()) {
                return rs.getInt("mCount");
            }

            return 0;
        } catch (SQLException e) {
            throw new DatabaseException(e);
        } finally {
            DbUtils.close(rs, pstmt);
        }
    }

    @Override
    public int inboxUnreadCount(User user) {
        String query = SystemGlobals.getSql("PrivateMessageModel.baseCount");
        query = query.replaceAll("#FILTER#", SystemGlobals.getSql("PrivateMessageModel.inboxUnread"));

        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try {
            pstmt = JForumExecutionContext.getConnection().prepareStatement(query);
            pstmt.setInt(1, user.getId());

            rs = pstmt.executeQuery();
            if (rs.next()) {
                return rs.getInt("mCount");
            }

            return 0;
        } catch (SQLException e) {
            throw new DatabaseException(e);
        } finally {
            DbUtils.close(rs, pstmt);
        }
    }
}
