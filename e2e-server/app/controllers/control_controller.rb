class ControlController < ActionController::API
  SINK = Concurrent::Array.new

  def restart
    ActionCable.server.restart
    head :no_content
  end

  def connections
    render plain: ActionCable.server.connections.count { |c| c.send(:websocket).alive? }.to_s
  end

  def disconnect
    ActionCable.server.remote_connections.where(client_id: params[:client_id])
      .disconnect(reconnect: params[:reconnect] == "true")
    head :no_content
  end

  def redirect
    redirect_to params[:to], allow_other_host: true
  end

  def sink
    SINK << { authorization: request.authorization, cookie: request.headers["Cookie"], query: request.query_string }
    head :not_found
  end

  def sunk
    render json: SINK
  end
end
