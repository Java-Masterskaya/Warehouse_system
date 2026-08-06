-- V48: Archive management after successful verification

ANALYZE stock_movements_archive;

SELECT public.run_maintenance(
               p_parent_table => 'public.stock_movements'
       );
