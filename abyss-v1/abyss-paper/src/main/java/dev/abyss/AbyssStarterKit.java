package dev.abyss;

import java.sql.*;
import java.util.UUID;

/** Grants starter equipment exactly once per player, independent of subsequent deletions. */
public final class AbyssStarterKit {
    private final AbyssPlugin plugin;
    private final String url;
    private static final String[] STARTER = {"dqmvi_w_0151", "dqmvi_a_0001"};
    public AbyssStarterKit(AbyssPlugin plugin,String url){this.plugin=plugin;this.url=url;try(Connection c=DriverManager.getConnection(url);Statement st=c.createStatement()){
        st.execute("CREATE TABLE IF NOT EXISTS abyss_starter_claims(owner_uuid TEXT PRIMARY KEY)");
    }catch(SQLException e){throw new IllegalStateException(e);}}
    public void ensure(UUID id){
        try(Connection c=DriverManager.getConnection(url)){
            c.setAutoCommit(false);
            try(PreparedStatement claim=c.prepareStatement("INSERT OR IGNORE INTO abyss_starter_claims(owner_uuid) VALUES(?)");
                PreparedStatement insert=c.prepareStatement("INSERT OR IGNORE INTO abyss_inventory(item_id,owner_uuid,definition_id,acquired_at) VALUES(?,?,?,?)")){
                claim.setString(1,id.toString());
                if(claim.executeUpdate()==1){
                    for(String def:STARTER){insert.setString(1,UUID.randomUUID().toString());insert.setString(2,id.toString());insert.setString(3,def);insert.setLong(4,System.currentTimeMillis());insert.addBatch();}
                    insert.executeBatch();
                }
                c.commit();
            }catch(Exception e){c.rollback();throw e;}
        }catch(Exception e){plugin.getLogger().severe("Starter equipment failed for "+id+": "+e.getMessage());}
    }
}
