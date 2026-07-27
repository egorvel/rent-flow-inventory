CREATE ROLE inventory LOGIN PASSWORD 'inventory-test';
CREATE SCHEMA inventory AUTHORIZATION inventory;

CREATE SCHEMA rental AUTHORIZATION rentflow_admin;
CREATE TABLE rental.sentinel (
    id integer PRIMARY KEY,
    marker varchar(32) NOT NULL
);
INSERT INTO rental.sentinel (id, marker) VALUES (1, 'untouched');
REVOKE ALL ON SCHEMA rental FROM PUBLIC;
