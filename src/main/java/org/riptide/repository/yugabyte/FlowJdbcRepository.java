package org.riptide.repository.yugabyte;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface FlowJdbcRepository extends CrudRepository<FlowEntitiy, UUID> {

}
