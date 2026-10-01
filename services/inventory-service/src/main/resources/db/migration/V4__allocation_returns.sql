-- Reverse pick (OUT-EX-02): picked stock of a cancelled order goes back to stock; the allocation ends RETURNED.
alter table allocation drop constraint allocation_status_check;
alter table allocation add constraint allocation_status_check
    check (status in ('OPEN', 'PICKED', 'RELEASED', 'ISSUED', 'RETURNED'));
