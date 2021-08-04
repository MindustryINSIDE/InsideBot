package inside.data.type.descriptor;

import com.fasterxml.jackson.databind.JsonNode;
import org.hibernate.type.descriptor.*;
import org.hibernate.type.descriptor.java.JavaTypeDescriptor;
import org.hibernate.type.descriptor.sql.BasicBinder;

import java.io.Serial;
import java.sql.*;

public class JsonSqlTypeDescriptor extends AbstractJsonSqlTypeDescriptor{
    @Serial
    private static final long serialVersionUID = 925608129281277893L;

    public static final JsonSqlTypeDescriptor instance = new JsonSqlTypeDescriptor();

    @Override
    public <X> ValueBinder<X> getBinder(JavaTypeDescriptor<X> descriptor){
        return new BasicBinder<>(descriptor, this){
            @Override
            protected void doBind(PreparedStatement statement, X value, int index, WrapperOptions options) throws SQLException{
                statement.setObject(index, descriptor.unwrap(value, JsonNode.class, options), getSqlType());
            }

            @Override
            protected void doBind(CallableStatement statement, X value, String name, WrapperOptions options) throws SQLException{
                statement.setObject(name, descriptor.unwrap(value, JsonNode.class, options), getSqlType());
            }
        };
    }
}
