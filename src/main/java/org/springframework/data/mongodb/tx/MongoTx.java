package org.springframework.data.mongodb.tx;

import org.springframework.transaction.annotation.Transactional;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;


@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.METHOD, ElementType.TYPE })
@Transactional
public @interface MongoTx {
  String DEFAULT_WRITE_CONCERN = "default";

  String readPreference() default "primary";

  String writeConcern() default DEFAULT_WRITE_CONCERN;

}
